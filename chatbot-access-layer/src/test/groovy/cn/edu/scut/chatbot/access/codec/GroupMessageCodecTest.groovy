package cn.edu.scut.chatbot.access.codec

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * 守护纪律：群消息翻译保真（SC-001 / data-model.md 第 4 节映射表）。
 * 标准字段全量翻译、平台特有语义只进 extension（ext.qq.*）、occurredAt 秒级 time 换算毫秒、
 * dedupKey 取平台 message_id（不自造幂等键——宪法第二条）、多模态段完整映射。
 */
class GroupMessageCodecTest extends Specification {

    def parser = new OneBotMessageParser()
    def codec = new GroupMessageCodec(parser)
    def mapper = new ObjectMapper()

    private def translate(String sampleJson, long receivedAtMillis) {
        def event = mapper.readTree(sampleJson)
        return codec.toEnvelope(event, receivedAtMillis, "3849281194743615489")
    }

    def "混合消息报文翻译全字段断言"() {
        given:
        def sample = '''{
            "time": 1717200000,
            "self_id": 10000,
            "post_type": "message",
            "message_type": "group",
            "sub_type": "normal",
            "message_id": 1234567,
            "user_id": 10001000,
            "group_id": 123456789,
            "anonymous": null,
            "message": [
                {"type": "at", "data": {"qq": "10000"}},
                {"type": "text", "data": {"text": " 看看这张图"}},
                {"type": "image", "data": {"url": "https://multimedia.nt.qq.com.cn/img", "file": "6B4DE3DF.jpg", "file_size": 204800}}
            ],
            "raw_message": "[CQ:at,qq=10000] 看看这张图[CQ:image,file=6B4DE3DF.jpg]",
            "sender": {"user_id": 10001000, "nickname": "滑稽", "card": "", "sex": "female", "age": 22, "area": "", "level": "", "role": "member", "title": ""}
        }'''

        when:
        def envelope = translate(sample, 1717200000412L)

        then: "信封标准字段"
        envelope.eventId() == "3849281194743615489"
        envelope.outboxId() == null
        envelope.dedupKey() == "1234567"
        envelope.platform() == "onebot-v11"
        envelope.botId() == "10000"
        envelope.groupId() == "123456789"
        envelope.userId() == "10001000"
        envelope.type() == "group_message"
        envelope.occurredAt() == 1717200000000L   // 秒级 time ×1000 换算毫秒
        envelope.receivedAt() == 1717200000412L
        envelope.extension().isEmpty()

        and: "载荷三段完整映射"
        def payload = envelope.payload()
        payload.messageId() == "1234567"
        payload.subType() == "normal"
        payload.parts().size() == 3
        payload.parts()[0].targetUserId() == "10000"
        payload.parts()[1].text() == " 看看这张图"
        def image = payload.parts()[2]
        image.originalUrl() == "https://multimedia.nt.qq.com.cn/img"
        image.fileName() == "6B4DE3DF.jpg"
        image.fileSize() == 204800L
        image.transferStatus() == "pending"
        payload.rawText() == "[CQ:at,qq=10000] 看看这张图[CQ:image,file=6B4DE3DF.jpg]"

        and: "SenderProfile 全字段"
        payload.sender().userId() == "10001000"
        payload.sender().nickname() == "滑稽"
        payload.sender().card() == ""
        payload.sender().role() == "member"
    }

    def "匿名消息anonymous结构进ext.qq.anonymous且subType为anonymous"() {
        given:
        def sample = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "anonymous", "message_id": 1110002,
            "user_id": 10001000, "group_id": 123456789,
            "anonymous": {"id": 97777, "name": "神秘人", "flag": "ANON-FLAG-1"},
            "message": [{"type": "text", "data": {"text": "匿名的发言"}}],
            "raw_message": "匿名的发言",
            "sender": {"user_id": 10001000, "nickname": "匿名者", "card": "", "role": "member"}
        }'''

        when:
        def envelope = translate(sample, 1717200000412L)

        then:
        def payload = envelope.payload()
        payload.subType() == "anonymous"
        envelope.extension()["ext.qq.anonymous"].contains("神秘人")
        envelope.extension()["ext.qq.anonymous"].contains("97777")
        and: "标准 sender 字段不承载匿名语义（best-effort 原样翻译）"
        payload.sender().nickname() == "匿名者"
    }

    def "message_sent事件标记ext.qq.self_sent"() {
        given:
        def sample = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message_sent",
            "message_type": "group", "sub_type": "normal", "message_id": 5550001,
            "user_id": 10000, "group_id": 123456789, "anonymous": null,
            "message": [{"type": "text", "data": {"text": "我自己发的"}}],
            "raw_message": "我自己发的",
            "sender": {"user_id": 10000, "nickname": "fake-bot", "card": "", "role": "member"}
        }'''

        when:
        def envelope = translate(sample, 1717200000412L)

        then:
        envelope.type() == "group_message"
        envelope.extension()["ext.qq.self_sent"] == "true"
    }

    def "图片段无url时转存状态为failed"() {
        given:
        def sample = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "normal", "message_id": 1110003,
            "user_id": 10001000, "group_id": 123456789, "anonymous": null,
            "message": [{"type": "image", "data": {"file": "NOURL.jpg"}}],
            "raw_message": "[CQ:image,file=NOURL.jpg]",
            "sender": {"user_id": 10001000, "nickname": "滑稽", "card": "", "role": "member"}
        }'''

        when:
        def envelope = translate(sample, 1717200000412L)

        then:
        def image = envelope.payload().parts()[0]
        image.originalUrl() == null
        image.transferStatus() == "failed"
        image.fileName() == "NOURL.jpg"
    }

    def "message_id字符串与数字双形态翻译结果等价"() {
        given:
        def numberForm = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "normal", "message_id": 1234567,
            "user_id": 10001000, "group_id": 123456789, "anonymous": null,
            "message": [{"type": "text", "data": {"text": "hi"}}],
            "raw_message": "hi",
            "sender": {"user_id": 10001000, "nickname": "n", "card": "", "role": "member"}
        }'''
        def stringForm = numberForm.replace('"message_id": 1234567', '"message_id": "1234567"')

        when:
        def fromNumber = translate(numberForm, 1L)
        def fromString = translate(stringForm, 1L)

        then:
        fromNumber.dedupKey() == "1234567"
        fromString.dedupKey() == "1234567"
        fromNumber.payload().messageId() == fromString.payload().messageId()
    }

    def "未建模段保真为unsupported"() {
        given:
        def sample = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "normal", "message_id": 1110004,
            "user_id": 10001000, "group_id": 123456789, "anonymous": null,
            "message": [
                {"type": "text", "data": {"text": "转个 Json 卡片"}},
                {"type": "json", "data": {"data": "{\\"prompt\\":\\"[\\"hi\\"]\\"}"}}
            ],
            "raw_message": "转个 Json 卡片[CQ:json,data=...]",
            "sender": {"user_id": 10001000, "nickname": "滑稽", "card": "", "role": "member"}
        }'''

        when:
        def envelope = translate(sample, 1L)

        then:
        def unsupported = envelope.payload().parts()[1]
        unsupported.part() == "unsupported"
        unsupported.rawType() == "json"
        unsupported.rawData()["data"].contains("hi")
    }

    def "at全体成员翻译为all标记不丢语义"() {
        given:
        def sample = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "normal", "message_id": 1110005,
            "user_id": 10001000, "group_id": 123456789, "anonymous": null,
            "message": [{"type": "at", "data": {"qq": "all"}}],
            "raw_message": "[CQ:at,qq=all]",
            "sender": {"user_id": 10001000, "nickname": "滑稽", "card": "", "role": "member"}
        }'''

        when:
        def envelope = translate(sample, 1L)

        then:
        def at = envelope.payload().parts()[0]
        at.targetUserId() == cn.edu.scut.chatbot.contract.message.AtPart.TARGET_ALL
    }

    def "reply引用段face表情段record语音段video视频段各自映射标准段"() {
        given: "一条五段混合消息"
        def sample = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "normal", "message_id": 1110006,
            "user_id": 10001000, "group_id": 123456789, "anonymous": null,
            "message": [
                {"type": "reply", "data": {"id": "7654321"}},
                {"type": "face", "data": {"id": "76"}},
                {"type": "record", "data": {"url": "https://multimedia.nt.qq.com.cn/a.silk", "file": "A.silk", "file_size": 40960}},
                {"type": "video", "data": {"url": "https://multimedia.nt.qq.com.cn/v.mp4", "file": "V.mp4", "file_size": 1048576}},
                {"type": "text", "data": {"text": "看上面的引用"}}
            ],
            "raw_message": "混合",
            "sender": {"user_id": 10001000, "nickname": "滑稽", "card": "", "role": "member"}
        }'''

        when:
        def parts = translate(sample, 1L).payload().parts()

        then:
        parts.size() == 5
        parts[0] instanceof cn.edu.scut.chatbot.contract.message.QuotePart
                parts[0].referencedMessageId() == "7654321"
        parts[1] instanceof cn.edu.scut.chatbot.contract.message.FacePart
                parts[1].faceId() == "76"
        parts[2] instanceof cn.edu.scut.chatbot.contract.message.AudioPart
                parts[2].transferStatus() == "pending"
        parts[2].fileSize() == 40960L
        parts[3] instanceof cn.edu.scut.chatbot.contract.message.VideoPart
                parts[3].transferStatus() == "pending"
        parts[3].fileName() == "V.mp4"
    }

    def "file_size非数字时容忍为null不炸翻译"() {
        given: "CQ string 形态下 file_size 出现非数字（协议端实现偏差）"
        def sample = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "normal", "message_id": 1110007,
            "user_id": 10001000, "group_id": 123456789, "anonymous": null,
            "message": "[CQ:image,file=X.jpg,file_size=huge]",
            "raw_message": "[CQ:image,file=X.jpg,file_size=huge]",
            "sender": {"user_id": 10001000, "nickname": "滑稽", "card": "", "role": "member"}
        }'''

        when:
        def envelope = translate(sample, 1L)

        then:
        def image = envelope.payload().parts()[0]
        image.fileName() == "X.jpg"
        image.fileSize() == null
    }

    def "message为CQ码string形态的群消息与array形态翻译结果等价"() {
        given: "同一消息的两种协议形态"
        def arrayForm = '''{
            "time": 1717200000, "self_id": 10000, "post_type": "message",
            "message_type": "group", "sub_type": "normal", "message_id": 1110008,
            "user_id": 10001000, "group_id": 123456789, "anonymous": null,
            "message": [{"type": "at", "data": {"qq": "10000"}}, {"type": "text", "data": {"text": " 你好"}}],
            "raw_message": "[CQ:at,qq=10000] 你好",
            "sender": {"user_id": 10001000, "nickname": "n", "card": "", "role": "member"}
        }'''
        def stringForm = arrayForm.replace(
                '"message": [{"type": "at", "data": {"qq": "10000"}}, {"type": "text", "data": {"text": " 你好"}}],',
                '"message": "[CQ:at,qq=10000] 你好",')

        when:
        def fromArray = translate(arrayForm, 1L).payload()
        def fromString = translate(stringForm, 1L).payload()

        then: "双形态解析出同一内部表示——协议端配置漂移不影响翻译结果"
        fromString.parts() == fromArray.parts()
        fromString.parts().size() == 2
        fromString.parts()[0].targetUserId() == "10000"
        fromString.parts()[1].text() == " 你好"
    }
}
