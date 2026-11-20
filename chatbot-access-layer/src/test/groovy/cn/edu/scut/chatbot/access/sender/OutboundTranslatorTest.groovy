package cn.edu.scut.chatbot.access.sender

import cn.edu.scut.chatbot.access.mediastore.SafeFetcher
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * 守护纪律：出原语到平台 API 的翻译（契约 onebot-ws-adapter.md 第 3 节 + data-model 第 7 节）。
 * 契约字段保持平台无关：isCancel 与平台参数 set 取反在接入层内完成；出方向媒体一次性
 * base64:// 内联（OneBot 11 无独立上传 API，无第二跳）；SendPrivateMessage 第一版拒绝。
 */
class OutboundTranslatorTest extends Specification {

    def objectMapper = new ObjectMapper()
    def mediaRoot = Files.createTempDirectory("outbound-media")
    /** 内联上限给 1MB：测试媒体只有几个字节 */
    def translator = new OutboundTranslator(1024 * 1024, mediaRoot.toString())

    def "storedRef逃逸出媒体卷时拒绝读取防止任意文件外泄"() {
        given: "卷外存在一个敏感文件，出事件段的 storedRef 指向它"
        def outsideRoot = Files.createTempDirectory("outside-media")
        def secretFile = outsideRoot.resolve("secret.env")
        Files.write(secretFile, "SECRET=1".bytes)
        def escapePart = new cn.edu.scut.chatbot.contract.message.ImagePart(
                "image", null, secretFile.toString(), "none", "secret.env", null)

        when:
        translator.translateSendGroupMessage("123456789", List.of(escapePart), null)

        then: "路径约束拦截（宪法第六条硬防线：被骗了也干不成坏事），不产出 base64 内容"
        def e = thrown(cn.edu.scut.chatbot.access.infrastructure.ChatBotException)
        e.errorCode == cn.edu.scut.chatbot.contract.error.ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED
        e.message.contains("逃逸")

        cleanup:
        outsideRoot.toFile().deleteDir()
    }

    def "storedRef带点点路径穿越卷根时同样拒绝"() {
        given: "相对路径试图穿越到卷外（mediaRoot/../outside.bin）"
        def escapePart = new cn.edu.scut.chatbot.contract.message.ImagePart(
                "image", null, mediaRoot.resolve("../escape.bin").toString(), "none", "escape.bin", null)

        when:
        translator.translateSendGroupMessage("123456789", List.of(escapePart), null)

        then:
        thrown(cn.edu.scut.chatbot.access.infrastructure.ChatBotException)
    }

    def cleanup() {
        mediaRoot.toFile().deleteDir()
    }

    private JsonNode paramsOf(String paramsJson) {
        objectMapper.readTree(paramsJson)
    }

    def "发群消息翻译为send_group_msg段数组且引用回复转reply段"() {
        given: "含文本与本地媒体引用的出事件"
        when:
        def call = translator.translateSendGroupMessage("123456789", List.of(), "7654321")
        def params = paramsOf(call.paramsJson())

        then:
        call.action() == "send_group_msg"
        params.path("group_id").asText() == "123456789"
        params.path("auto_escape").asBoolean() == false
        // replyToMessageId 翻译为消息段数组头部的 reply 段
        params.path("message").get(0).path("type").asText() == "reply"
        params.path("message").get(0).path("data").path("id").asText() == "7654321"
    }

    def "出方向媒体按storedRef读取文件并base64内联进消息段"() {
        given: "本地存储卷上有一张真实图片"
        byte[] imageBytes = [0x89, 0x50, 0x4E, 0x47] as byte[]
        Path imageFile = mediaRoot.resolve("gen-1.png")
        Files.write(imageFile, imageBytes)

        and: "指向该文件的图片段"
        def imagePart = new cn.edu.scut.chatbot.contract.message.ImagePart(
                "image", null, imageFile.toString(), "none", "gen-1.png", null)

        when:
        def call = translator.translateSendGroupMessage("123456789", List.of(imagePart), null)
        def imageSegment = paramsOf(call.paramsJson()).path("message").get(0)

        then:
        imageSegment.path("type").asText() == "image"
        imageSegment.path("data").path("file").asText().startsWith("base64://")
        imageSegment.path("data").path("file").asText() ==
                "base64://" + Base64.getEncoder().encodeToString(imageBytes)
    }

    def "本地媒体文件缺失时抛media_load_failed供回执分类"() {
        given:
        def missingPart = new cn.edu.scut.chatbot.contract.message.ImagePart(
                "image", null, mediaRoot.resolve("not-exist.png").toString(), "none", "x.png", null)

        when:
        translator.translateSendGroupMessage("123456789", List.of(missingPart), null)

        then:
        def e = thrown(cn.edu.scut.chatbot.access.infrastructure.ChatBotException)
        e.errorCode == cn.edu.scut.chatbot.contract.error.ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED
    }

    def "撤回消息翻译为delete_msg"() {
        when:
        def call = translator.translateRecallMessage("7654321")

        then:
        call.action() == "delete_msg"
        paramsOf(call.paramsJson()).path("message_id").asText() == "7654321"
    }

    def "表情回应翻译为set_msg_emoji_like且isCancel与平台set取反"() {
        expect:
        def setCall = translator.translateSetReaction("7654321", "76", false)
        setCall.action() == "set_msg_emoji_like"
        paramsOf(setCall.paramsJson()).path("set").asBoolean() == true

        def cancelCall = translator.translateSetReaction("7654321", "76", true)
        cancelCall.action() == "set_msg_emoji_like"
        paramsOf(cancelCall.paramsJson()).path("set").asBoolean() == false
    }

    def "互动回复按信封groupId判定好友或加群请求API"() {
        expect: "groupId 为空 → 好友请求"
        def friendCall = translator.translateInteractionReply(null, "FLAG-1", true, null)
        friendCall.action() == "set_friend_add_request"
        paramsOf(friendCall.paramsJson()).path("flag").asText() == "FLAG-1"
        paramsOf(friendCall.paramsJson()).path("approve").asBoolean() == true

        and: "groupId 非空 → 加群请求（加群请求必然关联群，判据用信封标准字段）"
        def groupCall = translator.translateInteractionReply("123456789", "FLAG-2", false, "不想加")
        groupCall.action() == "set_group_add_request"
        paramsOf(groupCall.paramsJson()).path("flag").asText() == "FLAG-2"
        paramsOf(groupCall.paramsJson()).path("approve").asBoolean() == false
        paramsOf(groupCall.paramsJson()).path("reason").asText() == "不想加"
    }

    def "互动回复的reason为空白时不写入params"() {
        when: "拒绝理由为空白字符串——不该出现空的 reason 键"
        def call = translator.translateInteractionReply(null, "FLAG-3", false, "  ")

        then:
        paramsOf(call.paramsJson()).has("reason") == false
    }

    def "at表情引用文本段按平台方言直译"() {
        given: "四种非媒体段混合"
        def parts = List.of(
                new cn.edu.scut.chatbot.contract.message.AtPart("at", "10001000"),
                new cn.edu.scut.chatbot.contract.message.FacePart("face", "76"),
                new cn.edu.scut.chatbot.contract.message.QuotePart("quote", "7654321"),
                new cn.edu.scut.chatbot.contract.message.TextPart("text", "文本"))

        when:
        def params = paramsOf(translator.translateSendGroupMessage("123456789", parts, null).paramsJson())

        then:
        params.path("message").size() == 4
        params.path("message").get(0).path("type").asText() == "at"
        params.path("message").get(0).path("data").path("qq").asText() == "10001000"
        params.path("message").get(1).path("type").asText() == "face"
        params.path("message").get(1).path("data").path("id").asText() == "76"
        params.path("message").get(2).path("type").asText() == "reply"
        params.path("message").get(2).path("data").path("id").asText() == "7654321"
        params.path("message").get(3).path("type").asText() == "text"
        params.path("message").get(3).path("data").path("text").asText() == "文本"
    }

    def "语音段翻译为record视频段翻译为video且均base64内联"() {
        given:
        def audioFile = mediaRoot.resolve("a.silk")
        Files.write(audioFile, [0x01, 0x02] as byte[])
        def videoFile = mediaRoot.resolve("v.mp4")
        Files.write(videoFile, [0x03] as byte[])

        when:
        def params = paramsOf(translator.translateSendGroupMessage("123456789", List.of(
                new cn.edu.scut.chatbot.contract.message.AudioPart("audio", null, audioFile.toString(), "none", "a.silk", null),
                new cn.edu.scut.chatbot.contract.message.VideoPart("video", null, videoFile.toString(), "none", "v.mp4", null)
        ), null).paramsJson())

        then: "image→image、record→record、video→video 的段类型名对齐 OneBot 11 方言"
        params.path("message").get(0).path("type").asText() == "record"
        params.path("message").get(0).path("data").path("file").asText() == "base64://" + Base64.getEncoder().encodeToString([0x01, 0x02] as byte[])
        params.path("message").get(1).path("type").asText() == "video"
        params.path("message").get(1).path("data").path("file").asText() == "base64://" + Base64.getEncoder().encodeToString([0x03] as byte[])
    }

    def "未建模段出方向跳过不抛异常"() {
        given: "Agent 不应构造 UnsupportedPart，防御性容错——跳过而不是炸投递"
        def parts = List.of(
                new cn.edu.scut.chatbot.contract.message.UnsupportedPart("unsupported", "json", Map.of("data", "x")),
                new cn.edu.scut.chatbot.contract.message.TextPart("text", "只有文本会发出"))

        when:
        def params = paramsOf(translator.translateSendGroupMessage("123456789", parts, null).paramsJson())

        then:
        params.path("message").size() == 1
        params.path("message").get(0).path("data").path("text").asText() == "只有文本会发出"
    }

    def "媒体段缺storedRef时按load_failed拒绝"() {
        given: "Agent 侧组装 bug——媒体段只有 URL 没有 storedRef"
        def urlOnly = new cn.edu.scut.chatbot.contract.message.ImagePart(
                "image", "https://cdn.example.com/x.jpg", null, "none", "x.jpg", null)

        when:
        translator.translateSendGroupMessage("123456789", List.of(urlOnly), null)

        then:
        def e = thrown(cn.edu.scut.chatbot.access.infrastructure.ChatBotException)
        e.errorCode == cn.edu.scut.chatbot.contract.error.ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED
    }

    def "媒体文件超过内联上限时按load_failed拒绝"() {
        given: "内联上限 4 字节，文件 5 字节"
        def strictTranslator = new OutboundTranslator(4, mediaRoot.toString())
        def bigFile = mediaRoot.resolve("big.bin")
        Files.write(bigFile, [1, 2, 3, 4, 5] as byte[])
        def bigPart = new cn.edu.scut.chatbot.contract.message.ImagePart(
                "image", null, bigFile.toString(), "none", "big.bin", null)

        when:
        strictTranslator.translateSendGroupMessage("123456789", List.of(bigPart), null)

        then:
        def e = thrown(cn.edu.scut.chatbot.access.infrastructure.ChatBotException)
        e.errorCode == cn.edu.scut.chatbot.contract.error.ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED
    }
}
