package cn.edu.scut.chatbot.access.codec

import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.envelope.RawEventRef
import cn.edu.scut.chatbot.contract.in.RawEventReasonCodes
import spock.lang.Specification

import java.time.Clock

/**
 * 守护纪律：翻译失败不丢报文（SC-001「无法解析的事件收敛 RawEvent 保真」）。
 * 非法 JSON / 未知结构 / 翻译内部异常三种兜底都必须携带 rawRef 回指留档行——
 * 原文已先留档，Agent 可忽略但排障可回溯（data-model.md 第 4 节兜底原语）。
 */
class RawEventCodecTest extends Specification {

    def codec = new RawEventCodec(new SnowflakeIdGenerator(2, Clock.fixed(
            java.time.Instant.ofEpochMilli(1767225610000L), java.time.ZoneOffset.UTC)))
    def rawRef = new RawEventRef(PlatformCodes.ONEBOT_V11, "8830", RawEventRef.FORMAT_JSON)

    def "非法JSON兜底携带illegal_json与rawRef"() {
        when:
        def envelope = codec.toEnvelope('{"time": 1717200000, "self_id": 10', RawEventReasonCodes.ILLEGAL_JSON, rawRef, 1717200000412L)

        then:
        envelope.type() == PrimitiveTypeCodes.RAW_EVENT
        envelope.payload().reason() == "illegal_json"
        envelope.payload().rawRef() == rawRef
        envelope.platform() == PlatformCodes.ONEBOT_V11
        envelope.eventId() != null
        envelope.dedupKey() != null
        envelope.dedupKey().length() <= 128
        envelope.receivedAt() == 1717200000412L
    }

    def "未知结构兜底携带unknown_structure且best effort提取标准字段"() {
        given: "结构合法但不认识的报文（platform 特有新事件类型）"
        def rawJson = '''{"time": 1717200000, "self_id": 10000, "post_type": "future",
            "group_id": 123456789, "user_id": 20002000, "whatever": "napcat 扩展"}'''

        when:
        def envelope = codec.toEnvelope(rawJson, RawEventReasonCodes.UNKNOWN_STRUCTURE, rawRef, 1717200000412L)

        then: "可解析出的标准字段照常翻译"
        envelope.botId() == "10000"
        envelope.groupId() == "123456789"
        envelope.userId() == "20002000"
        envelope.occurredAt() == 1717200000000L

        and: "兜底三件套：type / reason / rawRef"
        envelope.type() == PrimitiveTypeCodes.RAW_EVENT
        envelope.payload().reason() == "unknown_structure"
        envelope.payload().rawRef() == rawRef
    }

    def "翻译内部异常兜底internal_error同样保真"() {
        when:
        def envelope = codec.toEnvelope('{"broken"', RawEventReasonCodes.INTERNAL_ERROR, rawRef, 1717200000412L)

        then:
        envelope.payload().reason() == "internal_error"
        envelope.payload().rawRef() == rawRef
        envelope.botId() != null
    }

    def "完全解析不出self_id时botId用unknown占位满足信封非空校验"() {
        when:
        def envelope = codec.toEnvelope('not json at all', RawEventReasonCodes.ILLEGAL_JSON, rawRef, 1L)

        then:
        envelope.botId() == "unknown"
        envelope.dedupKey() != null
    }

    def "dedupKey为报文内容哈希且同报文稳定同值"() {
        given:
        def sameJson = '{"time": 1717200000, "self_id": 10000, "post_type": "future"}'

        when:
        def first = codec.toEnvelope(sameJson, RawEventReasonCodes.UNKNOWN_STRUCTURE, rawRef, 1L)
        def second = codec.toEnvelope(sameJson, RawEventReasonCodes.UNKNOWN_STRUCTURE, rawRef, 2L)

        then:
        first.dedupKey() == second.dedupKey()
        first.dedupKey().length() <= 128
    }
}
