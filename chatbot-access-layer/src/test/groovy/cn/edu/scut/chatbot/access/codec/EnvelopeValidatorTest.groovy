package cn.edu.scut.chatbot.access.codec

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.error.ErrorCodes
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload
import cn.edu.scut.chatbot.contract.in.SenderProfile
import cn.edu.scut.chatbot.contract.message.TextPart
import cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload
import spock.lang.Specification

/**
 * 守护纪律：信封非空清单校验（data-model.md 第 2 节「验证规则」）。
 * 入信封校验是接入层组装后的内部断言（违反即 Codec bug）；出信封校验拦住 Agent 侧
 * 组装的坏信封（缺 outboxId / traceContext 即毒消息，防对端 bug 静默扩散）。
 */
class EnvelopeValidatorTest extends Specification {

    def validator = new EnvelopeValidator()

    private EventEnvelope inboundEnvelope(Map overrides = [:]) {
        def defaults = [
                eventId     : "1",
                outboxId    : null,
                dedupKey    : "1234567",
                platform    : PlatformCodes.ONEBOT_V11,
                botId       : "10000",
                groupId     : "123456789",
                userId      : "10001000",
                type        : PrimitiveTypeCodes.GROUP_MESSAGE,
                payload     : new GroupMessagePayload("1234567", "normal",
                        new SenderProfile("10001000", "滑稽", "", "member", "", ""),
                        [new TextPart(TextPart.TYPE_TEXT, "hi")], "[CQ:text]"),
                occurredAt  : 1717200000000L,
                receivedAt  : 1717200000412L,
                extension   : [:],
                rawRef      : null,
                traceContext: null
        ]
        def merged = defaults + overrides
        return new EventEnvelope(
                merged.eventId, merged.outboxId, merged.dedupKey, merged.platform,
                merged.botId, merged.groupId, merged.userId, merged.type, merged.payload,
                merged.occurredAt, merged.receivedAt, merged.extension, merged.rawRef, merged.traceContext)
    }

    def "全字段合规的入信封通过校验"() {
        expect:
        validator.validateInbound(inboundEnvelope())
    }

    def "入信封缺eventId即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(eventId: null))

        then:
        thrown(NonRetryableException)
    }

    def "入信封eventId为空白字符串同样拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(eventId: "   "))

        then:
        thrown(NonRetryableException)
    }

    def "入信封缺platform即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(platform: null))

        then:
        thrown(NonRetryableException)
    }

    def "入信封缺botId即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(botId: null))

        then:
        thrown(NonRetryableException)
    }

    def "入信封缺type即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(type: null))

        then:
        thrown(NonRetryableException)
    }

    def "入信封缺payload即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(payload: null))

        then:
        thrown(NonRetryableException)
    }

    def "入信封缺receivedAt即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(receivedAt: null))

        then:
        thrown(NonRetryableException)
    }

    def "入信封携带outboxId违反方向约束即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(outboxId: "999"))

        then:
        thrown(NonRetryableException)
    }

    def "入信封dedupKey为空即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(dedupKey: ""))

        then:
        thrown(NonRetryableException)
    }

    def "入信封dedupKey超过128字符即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(dedupKey: "x" * 129))

        then:
        thrown(NonRetryableException)
    }

    def "入信封dedupKey恰好128字符边界放行"() {
        when:
        validator.validateInbound(inboundEnvelope(dedupKey: "x" * 128))

        then:
        noExceptionThrown()
    }

    def "群维度事件缺groupId即拒绝"() {
        when:
        validator.validateInbound(inboundEnvelope(groupId: null))

        then:
        thrown(NonRetryableException)
    }

    def "member_event与group_event同属群维度缺groupId即拒绝"() {
        when: "成员事件缺群号"
        validator.validateInbound(inboundEnvelope(type: PrimitiveTypeCodes.MEMBER_EVENT,
                payload: new cn.edu.scut.chatbot.contract.in.MemberEventPayload("member_join", null, null),
                groupId: null))

        then:
        thrown(NonRetryableException)

        when: "群事件缺群号"
        validator.validateInbound(inboundEnvelope(type: PrimitiveTypeCodes.GROUP_EVENT,
                payload: new cn.edu.scut.chatbot.contract.in.GroupEventPayload("info_changed"),
                groupId: null))

        then:
        thrown(NonRetryableException)
    }

    def "非群维度入信封不要求groupId"() {
        when: "私聊互动类事件（如好友请求）无群号属正常"
        validator.validateInbound(inboundEnvelope(type: PrimitiveTypeCodes.INTERACTION,
                payload: new cn.edu.scut.chatbot.contract.in.InteractionPayload(
                        "friend_request", null, "30003000", "FLAG-1"),
                groupId: null))

        then:
        noExceptionThrown()
    }

    private EventEnvelope outboundEnvelope(Map overrides = [:]) {
        def defaults = [
                eventId     : null,
                outboxId    : "3849281194743617536",
                dedupKey    : null,
                platform    : PlatformCodes.ONEBOT_V11,
                botId       : "10000",
                groupId     : "123456789",
                userId      : null,
                type        : PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                payload     : new SendGroupMessagePayload([new TextPart(TextPart.TYPE_TEXT, "hello")], null),
                occurredAt  : null,
                receivedAt  : null,
                extension   : [:],
                rawRef      : null,
                traceContext: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        ]
        def merged = defaults + overrides
        return new EventEnvelope(
                merged.eventId, merged.outboxId, merged.dedupKey, merged.platform,
                merged.botId, merged.groupId, merged.userId, merged.type, merged.payload,
                merged.occurredAt, merged.receivedAt, merged.extension, merged.rawRef, merged.traceContext)
    }

    def "全字段合规的出信封通过校验"() {
        expect:
        validator.validateOutbound(outboundEnvelope())
    }

    def "出信封缺outboxId即拒绝"() {
        when:
        validator.validateOutbound(outboundEnvelope(outboxId: null))

        then:
        thrown(NonRetryableException)
    }

    def "出信封outboxId为空白字符串同样拒绝"() {
        when:
        validator.validateOutbound(outboundEnvelope(outboxId: " "))

        then:
        thrown(NonRetryableException)
    }

    def "出信封携带eventId违反方向约束即拒绝"() {
        when:
        validator.validateOutbound(outboundEnvelope(eventId: "3849281194743615489"))

        then:
        thrown(NonRetryableException)
    }

    def "出信封缺platform或botId或type或payload均拒绝"() {
        when:
        validator.validateOutbound(outboundEnvelope(platform: null))

        then:
        thrown(NonRetryableException)

        when:
        validator.validateOutbound(outboundEnvelope(botId: null))

        then:
        thrown(NonRetryableException)

        when:
        validator.validateOutbound(outboundEnvelope(type: null))

        then:
        thrown(NonRetryableException)

        when:
        validator.validateOutbound(outboundEnvelope(payload: null))

        then:
        thrown(NonRetryableException)
    }

    def "非SendGroupMessage的出信封不要求groupId"() {
        when: "撤回消息与表情回应不需要群号（平台消息 ID 已含归属）"
        validator.validateOutbound(outboundEnvelope(type: PrimitiveTypeCodes.RECALL_MESSAGE,
                payload: new cn.edu.scut.chatbot.contract.out.RecallMessagePayload("7654321"),
                groupId: null))

        then:
        noExceptionThrown()
    }

    def "出信封缺traceContext即按毒消息拒绝"() {
        when:
        validator.validateOutbound(outboundEnvelope(traceContext: null))

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE
    }

    def "SendGroupMessage缺groupId即拒绝"() {
        when:
        validator.validateOutbound(outboundEnvelope(groupId: null))

        then:
        thrown(NonRetryableException)
    }

    def "违规错误码恒为adapter.codec.invalid_envelope"() {
        when:
        validator.validateOutbound(outboundEnvelope(outboxId: null))

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE
    }
}
