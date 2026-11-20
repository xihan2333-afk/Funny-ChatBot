package cn.edu.scut.chatbot.access.codec

import cn.edu.scut.chatbot.access.archive.RawEventArchiver
import cn.edu.scut.chatbot.access.fixtures.SteadyClock
import cn.edu.scut.chatbot.access.infrastructure.RetryableException
import cn.edu.scut.chatbot.access.infrastructure.StreamGateway
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.envelope.RawEventRef
import cn.edu.scut.chatbot.contract.in.InteractionPayload
import cn.edu.scut.chatbot.contract.error.ErrorCodes
import cn.edu.scut.chatbot.contract.in.RawEventPayload
import cn.edu.scut.chatbot.contract.in.RawEventReasonCodes
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * 守护纪律：入站主流程编排顺序硬编码、不可调换（data-model.md 第 8 节事务边界 + T028）。
 * 原文留档必须在翻译之前（翻译崩了原文已在）；留档失败该事件直接丢弃（保真优先于进流，
 * 无原文的兜底信封违反 rawRef 必填契约）；meta_event 只留档不进信封；
 * XADD 失败不让单条事件中断消费循环。单元测试 Mock 一切协作者（宪法第七条）。
 */
class InboundPipelineTest extends Specification {

    def archiver = Mock(RawEventArchiver)
    def eventTranslator = Mock(EventTranslator)
    def rawEventCodec = Mock(RawEventCodec)
    def streamGateway = Mock(StreamGateway)
    def clock = new SteadyClock(1775001600000L)
    /** EnvelopeCodec 是进程内纯翻译对象（无 IO），用真实例让 XADD 的信封 JSON 可断言 */
    def envelopeCodec = new EnvelopeCodec()
    def mapper = new ObjectMapper()

    def pipeline = new InboundPipeline(archiver, eventTranslator, rawEventCodec,
            envelopeCodec, streamGateway, clock, "chatbot:in:onebot-v11")

    private EventEnvelope sampleEnvelope() {
        return new EventEnvelope(
                "3849281194743615489", null, "dedup-hash-0000000000000000000000abcdef",
                PlatformCodes.ONEBOT_V11, "10000", "123456789", "10001000",
                PrimitiveTypeCodes.INTERACTION,
                new InteractionPayload(InteractionPayload.SUB_POKE, "10000", "10001000", null),
                1775001600000L, 1775001600412L, [:], null, null)
    }

    def "平台事件先留档后翻译再入流顺序硬编码"() {
        given: "一条正常群消息事件帧"
        def frame = '{"time":1775001600,"self_id":10000,"post_type":"message","message_type":"group",' +
                '"message_id":1234567,"group_id":123456789,"user_id":10001000}'
        def node = mapper.readTree(frame)
        def rawRef = new RawEventRef(PlatformCodes.ONEBOT_V11, "8821", RawEventRef.FORMAT_JSON)

        when:
        pipeline.handlePlatformEvent(frame, node)

        then:
        "留档字段：平台、botId、方向 in、事件类型 message.group、平台事件时间换算毫秒" +
                "（stub 响应直接挂在断言上——then 裸断言会遮蔽 given 的 stub）"
        1 * archiver.archive(PlatformCodes.ONEBOT_V11, "10000", RawEventArchiver.DIRECTION_IN,
                "message.group", null, 1775001600000L, frame) >> rawRef

        then: "翻译在留档之后（顺序即事务边界）"
        1 * eventTranslator.translate(node, frame, rawRef, clock.millis()) >> sampleEnvelope()

        then: "XADD 写入的是信封 JSON"
        1 * streamGateway.appendEnvelope("chatbot:in:onebot-v11", { String json ->
            json.contains('"type":"interaction"') && json.contains('"eventId":"3849281194743615489"')
        })
    }

    def "留档失败时事件直接丢弃不翻译不入流"() {
        given: "MySQL 不可达导致留档抛异常"
        def frame = '{"time":1775001600,"self_id":10000,"post_type":"message","message_type":"group"}'
        def node = mapper.readTree(frame)
        archiver.archive(_, _, _, _, _, _, _) >> { throw new RetryableException("adapter.archive.write_failed", "库不可达") }

        when:
        pipeline.handlePlatformEvent(frame, node)

        then: "无原文的信封违反 rawRef 必填契约——保真优先于进流"
        0 * eventTranslator.translate(_, _, _, _)
        0 * streamGateway.appendEnvelope(_, _)
        noExceptionThrown()
    }

    def "metaEvent心跳与lifecycle只留档不翻译不进信封"() {
        given: "心跳帧（Transport 层消化语义的留档侧验证）"
        def frame = '{"time":1775001600,"self_id":10000,"post_type":"meta_event","meta_event_type":"heartbeat","interval":30000}'
        def node = mapper.readTree(frame)
        archiver.archive(_, _, _, _, _, _, _) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")

        when:
        pipeline.handlePlatformEvent(frame, node)

        then: "留档事件类型为 meta_event.heartbeat（断言自带响应，理由同上）"
        1 * archiver.archive(PlatformCodes.ONEBOT_V11, "10000", RawEventArchiver.DIRECTION_IN,
                "meta_event.heartbeat", null, 1775001600000L, frame) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")

        and: "到此为止——心跳高频帧不产信封"
        0 * eventTranslator.translate(_, _, _, _)
        0 * streamGateway.appendEnvelope(_, _)
    }

    def "畸形帧留档后以illegalJson兜底信封入流"() {
        given:
        def brokenFrame = '{"time": 1717200000, "self_id": 10'
        def rawRef = new RawEventRef(PlatformCodes.ONEBOT_V11, "8822", RawEventRef.FORMAT_JSON)
        def rawEventEnvelope = new EventEnvelope(
                "3849281194743615490", null, "hash", PlatformCodes.ONEBOT_V11, "unknown", null, null,
                PrimitiveTypeCodes.RAW_EVENT,
                new RawEventPayload(RawEventReasonCodes.ILLEGAL_JSON, rawRef),
                null, clock.millis(), [:], rawRef, null)

        when:
        pipeline.handleUnparseableFrame(brokenFrame)

        then:
        "畸形帧留档时事件类型与平台时间均不可得（null）。" +
                "断言自带响应——then 裸断言会遮蔽 given 的 stub（同 PendingRescannerTest 的既定经验）"
        1 * archiver.archive(PlatformCodes.ONEBOT_V11, "unknown", RawEventArchiver.DIRECTION_IN,
                null, null, null, brokenFrame) >> rawRef

        then:
        1 * rawEventCodec.toEnvelope(brokenFrame, RawEventReasonCodes.ILLEGAL_JSON, rawRef, clock.millis()) >> rawEventEnvelope

        then:
        1 * streamGateway.appendEnvelope("chatbot:in:onebot-v11", { it.contains('"type":"raw_event"') })
    }

    def "无主API响应只留档不产信封"() {
        given: "迟到 echo 的响应帧"
        def orphanFrame = '{"status": "ok", "retcode": 0, "echo": "echo-late"}'

        when:
        pipeline.handleOrphanApiResponse(orphanFrame)

        then:
        1 * archiver.archive(PlatformCodes.ONEBOT_V11, "unknown", RawEventArchiver.DIRECTION_IN,
                null, null, null, orphanFrame)
        0 * streamGateway.appendEnvelope(_, _)
        0 * eventTranslator.translate(_, _, _, _)
    }

    def "XADD失败不让单条事件中断流程不向上抛"() {
        given: "Redis 停机"
        def frame = '{"time":1775001600,"self_id":10000,"post_type":"notice","notice_type":"notify","sub_type":"poke"}'
        def node = mapper.readTree(frame)
        archiver.archive(_, _, _, _, _, _, _) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")
        eventTranslator.translate(_, _, _, _) >> sampleEnvelope()
        streamGateway.appendEnvelope(_, _) >> { throw new RetryableException(ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, "redis down") }

        when:
        pipeline.handlePlatformEvent(frame, node)

        then: "原文已留档、该事件不确认——异常被消化，消费循环继续"
        noExceptionThrown()
    }

    def "未知postType的帧留档事件类型取postType原样"() {
        given: "协议端新增的事件类别（本版本接入层不认识）"
        def frame = '{"time":1775001600,"self_id":10000,"post_type":"future_new_type"}'
        def node = mapper.readTree(frame)
        archiver.archive(_, _, _, _, _, _, _) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")
        eventTranslator.translate(_, _, _, _) >> sampleEnvelope()

        when:
        pipeline.handlePlatformEvent(frame, node)

        then:
        1 * archiver.archive(PlatformCodes.ONEBOT_V11, "10000", RawEventArchiver.DIRECTION_IN,
                "future_new_type", null, 1775001600000L, frame) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")
    }

    def "selfId不可得时留档botId用unknown占位"() {
        given: "报文里没有 self_id 字段"
        def frame = '{"time":1775001600,"post_type":"notice","notice_type":"bot_offline"}'
        def node = mapper.readTree(frame)
        archiver.archive(_, _, _, _, _, _, _) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")
        eventTranslator.translate(_, _, _, _) >> sampleEnvelope()

        when:
        pipeline.handlePlatformEvent(frame, node)

        then:
        1 * archiver.archive(PlatformCodes.ONEBOT_V11, "unknown", RawEventArchiver.DIRECTION_IN,
                "notice.bot_offline", null, 1775001600000L, frame) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")
    }
}
