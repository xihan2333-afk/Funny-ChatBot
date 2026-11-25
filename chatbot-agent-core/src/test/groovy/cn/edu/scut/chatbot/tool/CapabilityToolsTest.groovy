// 守护纪律：能力工具链——字节 → 假媒体转存 storedRef → outbox 出事件携带转存引用；
// 槽位未绑定 isError「能力未配置」业务正常分支（spec US7 场景 1/2/4）
package cn.edu.scut.chatbot.tool

import cn.edu.scut.chatbot.bootstrap.JsonMappers
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.engine.outbox.OutboxWriter
import cn.edu.scut.chatbot.llm.capability.ImageGenerator
import cn.edu.scut.chatbot.llm.capability.OpenAiCompatCapabilityClient
import cn.edu.scut.chatbot.llm.capability.SpeechSynthesizer
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta
import cn.edu.scut.chatbot.llm.gateway.StoredMediaStore
import cn.edu.scut.chatbot.llm.slot.LlmErrorCodes
import cn.edu.scut.chatbot.loop.AgentToolResult
import cn.edu.scut.chatbot.loop.ToolInvocation
import cn.edu.scut.chatbot.tool.builtin.CapabilityTools
import spock.lang.Specification

class CapabilityToolsTest extends Specification {

    def imageGenerator = Mock(ImageGenerator)
    def speechSynthesizer = Mock(SpeechSynthesizer)
    /** 假媒体转存：字节落内存，返回 storedRef */
    def storedBytes = [:]
    def mediaStore = (StoredMediaStore) { data, mimeType ->
        def ref = "media-test-" + (storedBytes.size() + 1)
        storedBytes[ref] = new String(data, "UTF-8")
        return ref
    }
    def outboxWriter = Mock(OutboxWriter)
    def imageTool = new CapabilityTools.GenerateImageTool(imageGenerator, mediaStore, outboxWriter)
    def voiceTool = new CapabilityTools.GenerateVoiceTool(speechSynthesizer, mediaStore, outboxWriter)

    def invocation(String argumentsJson) {
        return new ToolInvocation(argumentsJson, "qq", "10000", "80001",
                cn.edu.scut.chatbot.loop.RunConfig.ExecutionForm.TURN, 7001L, null, "generateImage")
    }

    def "generateImage 工具链：字节 → 转存 storedRef → outbox 出事件携带转存引用"() {
        given:
        imageGenerator.generate("image_gen", _ as LlmCallMeta, "一只猫", null) >> new ImageGenerator.ImageResult(
                [0x89, 0x50] as byte[], "image/png")

        when:
        AgentToolResult result = imageTool.execute(invocation('{"prompt":"一只猫"}'))

        then:
        !result.isError()
        result.content()[0].text().contains("media-test-1")
        1 * outboxWriter.enqueueOutbound("qq", "10000", "80001",
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE, _, 7001L, null) >> { arguments ->
            def payload = JsonMappers.shared().readTree((String) arguments[4])
            assert payload.path("parts").get(0).path("part").asText() == "image"
            assert payload.path("parts").get(0).path("storedRef").asText() == "media-test-1"
            assert payload.path("parts").get(0).path("transferStatus").asText() == "transferred"
            return 9001L
        }
        storedBytes["media-test-1"] != null
    }

    def "generateVoice 工具链：音频转存与投递路径与生图同构"() {
        given:
        speechSynthesizer.synthesize("voice_gen", _ as LlmCallMeta, "大家好", null) >> new SpeechSynthesizer.VoiceResult(
                [0x01, 0x02] as byte[], "audio/mpeg")

        when:
        AgentToolResult result = voiceTool.execute(invocation('{"text":"大家好"}'))

        then:
        !result.isError()
        1 * outboxWriter.enqueueOutbound("qq", "10000", "80001",
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE, _, 7001L, null) >> { arguments ->
            def payload = JsonMappers.shared().readTree((String) arguments[4])
            assert payload.path("parts").get(0).path("part").asText() == "audio"
            assert payload.path("parts").get(0).path("storedRef").asText() == "media-test-1"
            return 9002L
        }
    }

    def "槽位未绑定 isError 能力未配置（业务正常分支——与桩面行为一致）"() {
        given:
        imageGenerator.generate("image_gen", _ as LlmCallMeta, _, null) >> {
            throw new OpenAiCompatCapabilityClient.CapabilityException(LlmErrorCodes.CONFIG_MISSING,
                    "槽位未绑定 slot=image_gen")
        }

        when:
        AgentToolResult result = imageTool.execute(invocation('{"prompt":"一只猫"}'))

        then:
        result.isError()
        result.content()[0].text().contains("能力未配置")
        0 * outboxWriter._
    }

    def "转存失败按工具错误处理不阻塞循环（字节已在手只差落存储）"() {
        given:
        def failingStore = (StoredMediaStore) { data, mimeType -> null }
        def tool = new CapabilityTools.GenerateImageTool(imageGenerator, failingStore, outboxWriter)
        imageGenerator.generate("image_gen", _ as LlmCallMeta, "一只猫", null) >> new ImageGenerator.ImageResult(
                [0x89] as byte[], "image/png")

        when:
        AgentToolResult result = tool.execute(invocation('{"prompt":"一只猫"}'))

        then:
        result.isError()
        result.content()[0].text().contains("转存失败")
        0 * outboxWriter._
    }

    def "上游限流错误转 isError 携带归一码"() {
        given:
        imageGenerator.generate("image_gen", _ as LlmCallMeta, _, null) >> {
            throw new OpenAiCompatCapabilityClient.CapabilityException(LlmErrorCodes.RATE_LIMITED,
                    "上游错误 HTTP 429：Rate limit")
        }

        when:
        AgentToolResult result = imageTool.execute(invocation('{"prompt":"一只猫"}'))

        then:
        result.isError()
        result.content()[0].text().contains("llm.rate_limited")
    }
}
