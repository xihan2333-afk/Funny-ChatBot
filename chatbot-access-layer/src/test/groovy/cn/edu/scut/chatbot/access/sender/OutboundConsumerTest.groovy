package cn.edu.scut.chatbot.access.sender

import cn.edu.scut.chatbot.access.archive.RawEventArchiver
import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator
import cn.edu.scut.chatbot.access.bootstrap.TraceContextHelper
import cn.edu.scut.chatbot.access.codec.EnvelopeCodec
import cn.edu.scut.chatbot.access.codec.EnvelopeValidator
import cn.edu.scut.chatbot.access.fixtures.SteadyClock
import cn.edu.scut.chatbot.access.infrastructure.ChatBotException
import cn.edu.scut.chatbot.access.infrastructure.GroupSerialExecutor
import cn.edu.scut.chatbot.access.infrastructure.StreamGateway
import cn.edu.scut.chatbot.access.transport.OneBotApiClient
import cn.edu.scut.chatbot.access.transport.ReactionCapability
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.envelope.RawEventRef
import cn.edu.scut.chatbot.contract.error.ErrorCodes
import cn.edu.scut.chatbot.contract.in.DeliveryFailureKindCodes
import cn.edu.scut.chatbot.contract.message.TextPart
import cn.edu.scut.chatbot.contract.out.RecallMessagePayload
import cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload
import cn.edu.scut.chatbot.contract.out.SendPrivateMessagePayload
import cn.edu.scut.chatbot.contract.out.SetReactionPayload
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

/**
 * 守护纪律：出流消费「处理完成才 XACK」+ 无「发出即忘」路径（契约 redis-streams.md 第 4 节 + T037~T040）。
 * 每次投递（成功 / 失败 / 不支持）都回流 DeliveryReport；毒消息当场 XACK + failed(internal)
 * 不永久卡住 pending；全部完成才 XACK。单元测试 Mock 一切协作者（宪法第七条）：
 * 群串行泵 Mock 后同步执行捕获的任务（投递逻辑可同步断言，不依赖调度时序）。
 */
class OutboundConsumerTest extends Specification {

    def streamGateway = Mock(StreamGateway)
    def apiClient = Mock(OneBotApiClient)
    def translator = Mock(OutboundTranslator)
    def reactionCapability = Mock(ReactionCapability)
    def archiver = Mock(RawEventArchiver)
    def groupSerialExecutor = Mock(GroupSerialExecutor)

    def clock = new SteadyClock(1775001600000L)
    /** EnvelopeCodec / EnvelopeValidator / DeliveryReportFactory / TraceContextHelper 为进程内纯翻译对象，用真实例 */
    def mapper = new ObjectMapper()

    def consumer

    def setup() {
        def reportFactory = new DeliveryReportFactory(new SnowflakeIdGenerator(2, clock), clock)
        consumer = new OutboundConsumer(streamGateway, new EnvelopeCodec(), new EnvelopeValidator(),
                groupSerialExecutor, apiClient, translator, reportFactory, reactionCapability,
                archiver, new TraceContextHelper(), clock,
                "chatbot:in:onebot-v11", "chatbot:out:onebot-v11", "access-layer", "access-layer-1",
                60000, 16)
        archiver.archive(_, _, _, _, _, _, _) >> new RawEventRef(PlatformCodes.ONEBOT_V11, "1", "json")
        // 群串行泵替身：捕获投递任务并同步执行（任务即 deliver，同步跑完即可断言完整闭环）。
        // 用 *_ 通配避免 null 参数（如 userId 为空）与类型化通配符不匹配
        groupSerialExecutor.submit(*_) >> { args ->
            ((Runnable) args[3]).run()
            return CompletableFuture.completedFuture(null)
        }
    }

    private String envelopeJson(EventEnvelope envelope) {
        return new EnvelopeCodec().serialize(envelope)
    }

    private EventEnvelope sendGroupMessageEnvelope(String outboxId = "3849281194743617536") {
        return new EventEnvelope(
                null, outboxId, null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                new SendGroupMessagePayload([new TextPart(TextPart.TYPE_TEXT, "hello")], null),
                null, null, [:], null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
    }

    private OneBotApiClient.OneBotResponse platformResponse(int retcode, String dataJson) {
        def raw = '{"status":"' + (retcode == 0 ? "ok" : "failed") + '","retcode":' + retcode +
                ',"data":' + dataJson + ',"echo":"echo-1"}'
        return new OneBotApiClient.OneBotResponse(retcode == 0 ? "ok" : "failed", retcode,
                mapper.readTree(dataJson), "echo-1", "", raw)
    }

    /** 登记回执捕获桩：返回单元素数组占位，投递完成后取 [0] 即回流信封 JSON（given 时点尚无内容） */
    private Object[] reportWrittenToInStream() {
        def holder = new Object[1]
        streamGateway.appendEnvelope("chatbot:in:onebot-v11", _ as String) >> { String key, String json ->
            holder[0] = mapper.readTree(json)
            return null
        }
        return holder
    }

    def "发群消息投递成功回执success并且回执后才XACK"() {
        given: "出流一条 send_group_message 消息"
        def message = new StreamGateway.StreamMessage("10-0", envelopeJson(sendGroupMessageEnvelope()))
        translator.translateSendGroupMessage("123456789", _, null) >> new OutboundTranslator.ApiCall(
                "send_group_msg", '{"group_id":123456789}')
        apiClient.call("send_group_msg", _) >> CompletableFuture.completedFuture(
                platformResponse(0, '{"message_id":7654321}'))

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then: "平台响应原文落留档表（direction=out，dedup_key 填平台 message_id）"
        1 * archiver.archive(PlatformCodes.ONEBOT_V11, "10000", RawEventArchiver.DIRECTION_OUT,
                "api.send_group_message", "7654321", null, { it.contains('"message_id":7654321') })

        and: "回执回流：status=success、outboxId 关联正确、platformMessageId 非空、traceContext 沿用出事件"
        reportHolder[0] != null
        reportHolder[0].path("type").asText() == "delivery_report"
        reportHolder[0].path("outboxId").asText() == "3849281194743617536"
        reportHolder[0].path("payload").path("status").asText() == "success"
        reportHolder[0].path("payload").path("platformMessageId").asText() == "7654321"
        reportHolder[0].path("traceContext").asText() == "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

        and: "全部完成才 XACK"
        1 * streamGateway.acknowledge("chatbot:out:onebot-v11", "access-layer", ["10-0"])
    }

    def "平台审核拒绝retcode1403回执content_rejected不静默吞"() {
        given:
        def message = new StreamGateway.StreamMessage("10-0", envelopeJson(sendGroupMessageEnvelope()))
        translator.translateSendGroupMessage(_, _, _) >> new OutboundTranslator.ApiCall("send_group_msg", '{}')
        apiClient.call(_, _) >> CompletableFuture.completedFuture(
                platformResponse(1403, '{"message_id":null}'))

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then:
        reportHolder[0].path("payload").path("status").asText() == "failed"
        reportHolder[0].path("payload").path("failureKind").asText() == DeliveryFailureKindCodes.CONTENT_REJECTED
        1 * streamGateway.acknowledge(_, _, ["10-0"])
    }

    def "半截帧毒消息当场XACK且回执failedInternal且outboxId取空串"() {
        given: "信封 JSON 是半截帧——连 outboxId 都解析不出来"
        def poisonJson = '{"outboxId":"3849281194743617999","type":"send_group_mess'
        def message = new StreamGateway.StreamMessage("11-0", poisonJson)

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then: "毒消息必须 XACK——不让坏消息永久卡住 pending"
        1 * streamGateway.acknowledge("chatbot:out:onebot-v11", "access-layer", ["11-0"])

        and: "失败事实不丢：failed(internal)，outboxId 尽力提取失败取空串，回执仍要发"
        reportHolder[0].path("payload").path("status").asText() == "failed"
        reportHolder[0].path("payload").path("failureKind").asText() == DeliveryFailureKindCodes.INTERNAL
        reportHolder[0].path("outboxId").asText() == ""
        0 * apiClient.call(_, _)
    }

    def "未知type的可解析毒消息回执带尽力提取的outboxId"() {
        given: "JSON 合法、outboxId 可读，但 type 是本版本不认识的原语——deserialize 拒绝"
        def poisonJson = '{"outboxId":"3849281194743617999","type":"future_primitive","payload":{}}'
        def message = new StreamGateway.StreamMessage("11-1", poisonJson)

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then:
        1 * streamGateway.acknowledge("chatbot:out:onebot-v11", "access-layer", ["11-1"])
        reportHolder[0].path("payload").path("failureKind").asText() == DeliveryFailureKindCodes.INTERNAL
        reportHolder[0].path("outboxId").asText() == "3849281194743617999"
        0 * apiClient.call(_, _)
    }

    def "缺traceContext的信封按毒消息处理outboxId取空串回执仍要发"() {
        given: "信封结构合法但缺 traceContext（Agent 侧组装 bug）"
        def broken = new EventEnvelope(
                null, "3849281194743618000", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                new SendGroupMessagePayload([], null),
                null, null, [:], null, null)
        def message = new StreamGateway.StreamMessage("12-0", envelopeJson(broken))

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then:
        1 * streamGateway.acknowledge(_, _, ["12-0"])
        reportHolder[0].path("payload").path("failureKind").asText() == DeliveryFailureKindCodes.INTERNAL
        reportHolder[0].path("payload").path("status").asText() == "failed"
        0 * apiClient.call(_, _)
    }

    def "私聊消息第一版回执unsupported不再调用协议端"() {
        given:
        def envelope = new EventEnvelope(
                null, "3849281194743618001", null,
                PlatformCodes.ONEBOT_V11, "10000", null, "30003000",
                PrimitiveTypeCodes.SEND_PRIVATE_MESSAGE,
                new SendPrivateMessagePayload([], null),
                null, null, [:], null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        def message = new StreamGateway.StreamMessage("13-0", envelopeJson(envelope))

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then: "能力边界可感知：unsupported 不算 failed"
        reportHolder[0].path("payload").path("status").asText() == "unsupported"
        0 * apiClient.call(_, _)
        1 * streamGateway.acknowledge(_, _, ["13-0"])
    }

    def "SetReaction首调retcode1404标记能力降级并回执unsupported"() {
        given:
        def envelope = new EventEnvelope(
                null, "3849281194743618002", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SET_REACTION,
                new SetReactionPayload("7654321", "76", false),
                null, null, [:], null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        def message = new StreamGateway.StreamMessage("14-0", envelopeJson(envelope))
        reactionCapability.isEmojiLikeSupported() >> true
        translator.translateSetReaction("7654321", "76", false) >> new OutboundTranslator.ApiCall(
                "set_msg_emoji_like", '{"message_id":7654321}')
        apiClient.call("set_msg_emoji_like", _) >> CompletableFuture.completedFuture(
                platformResponse(1404, '{}'))

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then: "首调即拒（retcode 表无此 API）→ 标记该协议端不支持"
        1 * reactionCapability.markEmojiLikeUnsupported()

        and: "回执 unsupported（能力缺失不算失败）"
        reportHolder[0].path("payload").path("status").asText() == "unsupported"
        1 * streamGateway.acknowledge(_, _, ["14-0"])
    }

    def "能力已降级后同类出事件直接回执unsupported不再调用协议端"() {
        given:
        def envelope = new EventEnvelope(
                null, "3849281194743618003", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SET_REACTION,
                new SetReactionPayload("7654321", "76", false),
                null, null, [:], null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        def message = new StreamGateway.StreamMessage("15-0", envelopeJson(envelope))
        reactionCapability.isEmojiLikeSupported() >> false

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then:
        0 * apiClient.call(_, _)
        0 * translator.translateSetReaction(_, _, _)
        reportHolder[0].path("payload").path("status").asText() == "unsupported"
        1 * streamGateway.acknowledge(_, _, ["15-0"])
    }

    def "本地媒体装载失败按media_missing回执不无限重试"() {
        given: "翻译时读不到 storedRef 指向的本地文件"
        def message = new StreamGateway.StreamMessage("16-0", envelopeJson(sendGroupMessageEnvelope()))
        translator.translateSendGroupMessage(_, _, _) >> {
            throw new ChatBotException(ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED, "出方向媒体文件不存在")
        }

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then:
        reportHolder[0].path("payload").path("status").asText() == "failed"
        reportHolder[0].path("payload").path("failureKind").asText() == DeliveryFailureKindCodes.MEDIA_MISSING
        0 * apiClient.call(_, _)
        1 * streamGateway.acknowledge(_, _, ["16-0"])
    }

    def "出流出现入方向原语属对端组装错误回执failedInternal"() {
        given: "Agent 侧 bug 把入原语写进了出流"
        def envelope = new EventEnvelope(
                null, "3849281194743618004", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", "10001000",
                PrimitiveTypeCodes.GROUP_MESSAGE,
                new cn.edu.scut.chatbot.contract.in.GroupMessagePayload("1", "normal", null, [], "x"),
                null, null, [:], null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        def message = new StreamGateway.StreamMessage("17-0", envelopeJson(envelope))

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then:
        reportHolder[0].path("payload").path("failureKind").asText() == DeliveryFailureKindCodes.INTERNAL
        0 * apiClient.call(_, _)
        1 * streamGateway.acknowledge(_, _, ["17-0"])
    }

    def "XACK失败不抛异常回执已经回流"() {
        given:
        def message = new StreamGateway.StreamMessage("18-0", envelopeJson(sendGroupMessageEnvelope()))
        translator.translateSendGroupMessage(_, _, _) >> new OutboundTranslator.ApiCall("send_group_msg", '{}')
        apiClient.call(_, _) >> CompletableFuture.completedFuture(platformResponse(0, '{"message_id":7654321}'))
        streamGateway.acknowledge(_, _, _) >> { throw new IllegalStateException("redis 抖动") }
        streamGateway.appendEnvelope(_, _) >> null

        when:
        consumer.redispatch(message)

        then: "XACK 失败留给 pending 重投（至少一次），异常在 acknowledge 内部消化"
        noExceptionThrown()
    }

    def "撤回消息投递成功回执success且platformMessageId为空"() {
        given:
        def envelope = new EventEnvelope(
                null, "3849281194743618005", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.RECALL_MESSAGE,
                new RecallMessagePayload("7654321"),
                null, null, [:], null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        def message = new StreamGateway.StreamMessage("19-0", envelopeJson(envelope))
        translator.translateRecallMessage("7654321") >> new OutboundTranslator.ApiCall("delete_msg", '{"message_id":7654321}')
        apiClient.call("delete_msg", _) >> CompletableFuture.completedFuture(platformResponse(0, '{}'))

        def reportHolder = reportWrittenToInStream()

        when:
        consumer.redispatch(message)

        then:
        reportHolder[0].path("payload").path("status").asText() == "success"
        reportHolder[0].path("payload").path("platformMessageId").isNull()
        1 * streamGateway.acknowledge(_, _, ["19-0"])
    }
}
