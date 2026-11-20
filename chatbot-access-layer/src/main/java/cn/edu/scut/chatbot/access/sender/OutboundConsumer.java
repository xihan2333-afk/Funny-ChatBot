package cn.edu.scut.chatbot.access.sender;

import cn.edu.scut.chatbot.access.archive.RawEventArchiver;
import cn.edu.scut.chatbot.access.bootstrap.TraceContextHelper;
import cn.edu.scut.chatbot.access.codec.EnvelopeCodec;
import cn.edu.scut.chatbot.access.codec.EnvelopeValidator;
import cn.edu.scut.chatbot.access.codec.MessageTextSummary;
import cn.edu.scut.chatbot.access.infrastructure.ChatBotException;
import cn.edu.scut.chatbot.access.infrastructure.GroupSerialExecutor;
import cn.edu.scut.chatbot.access.infrastructure.StreamGateway;
import cn.edu.scut.chatbot.access.transport.OneBotApiClient;
import cn.edu.scut.chatbot.access.transport.ReactionCapability;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.contract.in.DeliveryFailureKindCodes;
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload;
import cn.edu.scut.chatbot.contract.out.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 出流消费与投递编排（契约 redis-streams.md 第 4 节）：
 *
 * <pre>
 * 常驻虚拟线程（stream-out-consumer）：XREADGROUP GROUP access-layer BLOCK 55000 COUNT 16
 *   消费线程只搬运：解析信封 → 校验 outboxId/traceContext 非空 → submit 进群串行泵
 * 群串行泵内投递：翻译 → API 调用 → 平台响应留档（direction=out）
 *   → DeliveryReport 回执回流 XADD 入流 → 全部完成才 XACK
 * </pre>
 *
 * <p>毒消息（解析失败 / 校验失败）直接 XACK + 回执 failed(internal, outboxId 取可解析部分或空)，
 * 不让单条坏消息永久卡住 pending。无「发出即忘」路径：失败、不支持同样回执。
 */
@Component
public class OutboundConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboundConsumer.class);

    private final StreamGateway streamGateway;
    private final EnvelopeCodec envelopeCodec;
    private final EnvelopeValidator validator;
    private final GroupSerialExecutor groupSerialExecutor;
    private final OneBotApiClient apiClient;
    private final OutboundTranslator translator;
    private final DeliveryReportFactory reportFactory;
    private final ReactionCapability reactionCapability;
    private final RawEventArchiver archiver;
    private final TraceContextHelper traceContextHelper;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 出方向续接 Span 用（消费出事件时从信封 traceContext 提取父上下文；无 agent 时 no-op）
     */
    private final Tracer tracer = GlobalOpenTelemetry.get().getTracer("chatbot-access-layer");

    private final String inStreamKey;
    private final String outStreamKey;
    private final String consumerGroup;
    private final String consumerName;
    private final long blockMillis;
    private final long readCount;

    /**
     * 已提交群泵、尚未完成的 recordId 窗口：pending 重投与正常消费共用分发路径，
     * 同一 recordId 在本进程内只允许一个在途投递任务（重投风暴下的进程内去重窗口——
     * 这是传输层防重复放大，不是业务幂等，业务幂等仍在 Agent 消费端）。
     */
    private final java.util.Set<String> inFlightRecordIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile boolean running = false;

    public OutboundConsumer(StreamGateway streamGateway,
                            EnvelopeCodec envelopeCodec,
                            EnvelopeValidator validator,
                            GroupSerialExecutor groupSerialExecutor,
                            OneBotApiClient apiClient,
                            OutboundTranslator translator,
                            DeliveryReportFactory reportFactory,
                            ReactionCapability reactionCapability,
                            RawEventArchiver archiver,
                            TraceContextHelper traceContextHelper,
                            Clock clock,
                            @Value("${access.stream.in-key}") String inStreamKey,
                            @Value("${access.stream.out-key}") String outStreamKey,
                            @Value("${access.stream.consumer-group}") String consumerGroup,
                            @Value("${access.stream.consumer-name}") String consumerName,
                            @Value("${access.stream.block-millis:55000}") long blockMillis,
                            @Value("${access.stream.read-count:16}") long readCount) {
        this.streamGateway = streamGateway;
        this.envelopeCodec = envelopeCodec;
        this.validator = validator;
        this.groupSerialExecutor = groupSerialExecutor;
        this.apiClient = apiClient;
        this.translator = translator;
        this.reportFactory = reportFactory;
        this.reactionCapability = reactionCapability;
        this.archiver = archiver;
        this.traceContextHelper = traceContextHelper;
        this.clock = clock;
        this.inStreamKey = inStreamKey;
        this.outStreamKey = outStreamKey;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.blockMillis = blockMillis;
        this.readCount = readCount;
    }

    // ===== SmartLifecycle =====

    @Override
    public void start() {
        running = true;
        Thread.ofVirtual().name("stream-out-consumer").start(this::consumeLoop);
    }

    @Override
    public void stop() {
        // 优雅停机（契约第 4 节）：停止 XREADGROUP（消费循环在当前 BLOCK 周期后自然退出），
        // 等待群串行泵排空（上限 30 秒）——泵内任务完成即 XACK，未完成留给重启后 pending 重投
        running = false;
        boolean drained = groupSerialExecutor.awaitDrain(30_000);
        if (!drained) {
            log.warn("停机等待超时：群串行泵仍有未完成任务，未完成消息留给重启后 pending 重投");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 消费循环：建组（幂等）→ 阻塞读 → 逐条搬运分发。Redis 故障按退避重试（至少一次）
     */
    private void consumeLoop() {
        try {
            streamGateway.ensureConsumerGroup(outStreamKey, consumerGroup);
        } catch (Exception e) {
            log.error("出流消费组建组失败，消费循环稍后重试。errorCode={}",
                    ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, e);
            sleepQuietly(5000);
            if (running) {
                consumeLoop();
            }
            return;
        }
        while (running) {
            try {
                List<StreamGateway.StreamMessage> messages = streamGateway.readNewMessages(
                        outStreamKey, consumerGroup, consumerName, readCount, blockMillis);
                for (StreamGateway.StreamMessage message : messages) {
                    dispatch(message);
                }
            } catch (Exception e) {
                log.error("出流 XREADGROUP 异常，5 秒后重试。errorCode={}",
                        ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, e);
                sleepQuietly(5000);
            }
        }
    }

    /**
     * 重投入口（pending XCLAIM 认领后的同一条分发路径——契约第 4 节）：
     * 与正常消费共用 dispatch，无独立重投逻辑。
     */
    public void redispatch(StreamGateway.StreamMessage message) {
        dispatch(message);
    }

    /**
     * 单条消息搬运：解析 → 校验 → submit 群串行泵（毒消息当场 XACK + 回执 failed(internal)）
     */
    private void dispatch(StreamGateway.StreamMessage message) {
        EventEnvelope envelope;
        try {
            envelope = envelopeCodec.deserialize(message.envelope());
            validator.validateOutbound(envelope);
        } catch (Exception e) {
            log.error("出流毒消息（解析或校验失败），XACK 并回执 failed(internal)。errorCode={}，recordId={}",
                    ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE, message.recordId(), e);
            acknowledge(message.recordId());
            emitReport(reportFactory.failed(parseOutboxIdBestEffort(message.envelope()),
                            DeliveryFailureKindCodes.INTERNAL, e.getMessage(), false),
                    "onebot-v11", "unknown", null, null);
            return;
        }
        // 进程内在途去重：pending 重投与正常消费竞争同一条消息时，第二个到达者直接跳过
        // （在途任务完成时会 XACK，消息不会丢；跳过避免同一条消息进群泵两次被平台收到两次）
        if (!inFlightRecordIds.add(message.recordId())) {
            log.info("recordId={} 已有在途投递任务（pending 重投竞争），本次跳过", message.recordId());
            return;
        }
        log.info("收到出事件：recordId={}，outboxId={}，type={}，group={}，bot={}，内容={}",
                message.recordId(), envelope.outboxId(), envelope.type(), envelope.groupId(),
                envelope.botId(), outboundTextOf(envelope));
        groupSerialExecutor.submit(envelope.botId(), envelope.groupId(), envelope.userId(),
                () -> {
                    try {
                        deliver(message.recordId(), envelope);
                    } finally {
                        inFlightRecordIds.remove(message.recordId());
                    }
                });
    }

    /**
     * 投递任务（群串行泵内执行）：从信封 traceContext 提取父上下文续接 platform.event.deliver
     * Span（R6 决策），翻译 → 调用 → 留档 → 回执 → XACK（全部完成才确认）。
     * 回执沿用原出事件 traceContext——全程一个 traceId。
     */
    private void deliver(String recordId, EventEnvelope envelope) {
        Span deliverSpan = tracer.spanBuilder("platform.event.deliver")
                .setSpanKind(SpanKind.PRODUCER)
                .setParent(traceContextHelper.extract(envelope.traceContext()))
                .startSpan();
        boolean reportEmitted = false;
        try (Scope ignored = deliverSpan.makeCurrent()) {
            DeliveryReportPayload report;
            try {
                report = performDelivery(envelope);
            } catch (ChatBotException e) {
                log.error("出事件投递失败。errorCode={}，outboxId={}", e.getErrorCode(), envelope.outboxId(), e);
                report = reportFactory.failed(envelope.outboxId(),
                        failureKindOf(e), e.getMessage(), false);
            } catch (java.util.concurrent.CompletionException completion) {
                // API 调用的连接级失败（ApiClient 已静默重试一次仍失败）：network + retried=true
                Throwable cause = completion.getCause() == null ? completion : completion.getCause();
                log.error("出事件投递网络失败（已静默重试一次）。errorCode={}，outboxId={}",
                        ErrorCodes.ADAPTER_TRANSPORT_CONNECT_FAILED, envelope.outboxId(), cause);
                report = reportFactory.failed(envelope.outboxId(),
                        DeliveryFailureKindCodes.NETWORK, cause.getMessage(), true);
            } catch (Exception e) {
                log.error("出事件投递异常。errorCode={}，outboxId={}",
                        ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, envelope.outboxId(), e);
                report = reportFactory.failed(envelope.outboxId(),
                        DeliveryFailureKindCodes.UNKNOWN, e.getMessage(), false);
            }
            reportEmitted = emitReport(report, envelope.platform(), envelope.botId(),
                    envelope.outboxId(), envelope.traceContext());
            log.info("出事件投递完成：outboxId={}，type={}，status={}，platformMessageId={}，retried={}",
                    envelope.outboxId(), envelope.type(), report.status(),
                    report.platformMessageId(), report.retried());
            if (reportEmitted) {
                // 回执已落流才算完成：回执 XADD 失败时不 XACK，留给 pending 重投重新走全流程
                acknowledge(recordId);
            }
        } finally {
            deliverSpan.end();
        }
    }

    /**
     * 按出原语类型分发投递，返回投递结果回执载荷
     */
    private DeliveryReportPayload performDelivery(EventEnvelope envelope) throws Exception {
        switch (envelope.type()) {
            case PrimitiveTypeCodes.SEND_GROUP_MESSAGE: {
                SendGroupMessagePayload payload = (SendGroupMessagePayload) envelope.payload();
                OutboundTranslator.ApiCall call = translator.translateSendGroupMessage(
                        envelope.groupId(), payload.parts(), payload.replyToMessageId());
                OneBotApiClient.OneBotResponse response = apiClient.call(call.action(), call.paramsJson()).join();
                archiveOutboundResponse(envelope, response.raw());
                if (response.isOk()) {
                    String platformMessageId = response.data().path("message_id").asText(null);
                    return reportFactory.success(envelope.outboxId(), platformMessageId);
                }
                return reportFactory.failed(envelope.outboxId(),
                        DeliveryReportFactory.classifyRetcodeFailure(response.retcode(), response.wording()),
                        "retcode=" + response.retcode() + " " + response.wording(), false);
            }
            case PrimitiveTypeCodes.SEND_PRIVATE_MESSAGE:
                // 第一版预留：拒绝并回执 unsupported（Agent 侧可感知能力边界）
                return reportFactory.unsupported(envelope.outboxId(), "第一版接入层不支持私聊消息投递");
            case PrimitiveTypeCodes.RECALL_MESSAGE: {
                RecallMessagePayload payload = (RecallMessagePayload) envelope.payload();
                OutboundTranslator.ApiCall call = translator.translateRecallMessage(payload.platformMessageId());
                OneBotApiClient.OneBotResponse response = apiClient.call(call.action(), call.paramsJson()).join();
                archiveOutboundResponse(envelope, response.raw());
                if (response.isOk()) {
                    return reportFactory.success(envelope.outboxId(), null);
                }
                return reportFactory.failed(envelope.outboxId(),
                        DeliveryReportFactory.classifyRetcodeFailure(response.retcode(), response.wording()),
                        "retcode=" + response.retcode() + " " + response.wording(), false);
            }
            case PrimitiveTypeCodes.SET_REACTION: {
                SetReactionPayload payload = (SetReactionPayload) envelope.payload();
                if (!reactionCapability.isEmojiLikeSupported()) {
                    // 首调即拒后已标记：不再调用，直接回执 unsupported
                    return reportFactory.unsupported(envelope.outboxId(),
                            "当前协议端不支持 set_msg_emoji_like（能力探测降级）");
                }
                OutboundTranslator.ApiCall call = translator.translateSetReaction(
                        payload.platformMessageId(), payload.emojiId(), payload.isCancel());
                OneBotApiClient.OneBotResponse response = apiClient.call(call.action(), call.paramsJson()).join();
                archiveOutboundResponse(envelope, response.raw());
                if (response.isOk()) {
                    return reportFactory.success(envelope.outboxId(), null);
                }
                if (ReactionCapability.isApiMissingRetcode(response.retcode())) {
                    reactionCapability.markEmojiLikeUnsupported();
                    return reportFactory.unsupported(envelope.outboxId(),
                            "协议端返回 retcode=" + response.retcode() + "（无此 API），已标记不支持");
                }
                return reportFactory.failed(envelope.outboxId(),
                        DeliveryReportFactory.classifyRetcodeFailure(response.retcode(), response.wording()),
                        "retcode=" + response.retcode() + " " + response.wording(), false);
            }
            case PrimitiveTypeCodes.SEND_INTERACTION_REPLY: {
                SendInteractionReplyPayload payload = (SendInteractionReplyPayload) envelope.payload();
                OutboundTranslator.ApiCall call = translator.translateInteractionReply(
                        envelope.groupId(), payload.requestFlag(), payload.approve(), payload.reason());
                OneBotApiClient.OneBotResponse response = apiClient.call(call.action(), call.paramsJson()).join();
                archiveOutboundResponse(envelope, response.raw());
                if (response.isOk()) {
                    return reportFactory.success(envelope.outboxId(), null);
                }
                return reportFactory.failed(envelope.outboxId(),
                        DeliveryReportFactory.classifyRetcodeFailure(response.retcode(), response.wording()),
                        "retcode=" + response.retcode() + " " + response.wording(), false);
            }
            default:
                // 出流里出现入方向原语属对端组装错误
                return reportFactory.failed(envelope.outboxId(),
                        DeliveryFailureKindCodes.INTERNAL, "出流出现非出原语 type=" + envelope.type(), false);
        }
    }

    /**
     * 出站日志的消息内容：发送类事件打消息段摘要（文本 / @ / 媒体形态），非发送事件记 "-"
     */
    private String outboundTextOf(EventEnvelope envelope) {
        if (envelope.payload() instanceof SendGroupMessagePayload send) {
            return MessageTextSummary.ofParts(send.parts());
        }
        if (envelope.payload() instanceof SendPrivateMessagePayload send) {
            return MessageTextSummary.ofParts(send.parts());
        }
        return "-";
    }

    /**
     * 出方向留档：平台 API 响应原文落 raw_event_log（direction=out，dedup_key 填平台 message_id）
     */
    private void archiveOutboundResponse(EventEnvelope envelope, String responseJson) {
        try {
            String platformMessageId = null;
            JsonNode node = json.readTree(responseJson == null ? "{}" : responseJson);
            if (node.path("data").has("message_id")) {
                platformMessageId = node.path("data").path("message_id").asText();
            }
            archiver.archive(envelope.platform(), envelope.botId(), RawEventArchiver.DIRECTION_OUT,
                    "api." + envelope.type(), platformMessageId, null, responseJson);
        } catch (Exception e) {
            // 留档失败不阻断回执闭环（审计落点尽力而为，回执才是结算关键路径）
            log.error("出方向留档失败。errorCode={}", ErrorCodes.ADAPTER_ARCHIVE_WRITE_FAILED, e);
        }
    }

    /**
     * 回执回流：DeliveryReport 信封 XADD 入流（无发出即忘路径）。@return 回执是否成功落流
     */
    private boolean emitReport(DeliveryReportPayload report, String platform, String botId,
                               String outboxId, String traceContext) {
        try {
            EventEnvelope reportEnvelope = reportFactory.toEnvelope(
                    platform == null ? "onebot-v11" : platform,
                    botId == null ? "unknown" : botId,
                    report,
                    traceContext);
            streamGateway.appendEnvelope(inStreamKey, envelopeCodec.serialize(reportEnvelope));
            return true;
        } catch (Exception e) {
            log.error("投递回执回流失败，消息不 XACK、留给 pending 重投。errorCode={}，outboxId={}",
                    ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, outboxId, e);
            return false;
        }
    }

    private void acknowledge(String recordId) {
        try {
            streamGateway.acknowledge(outStreamKey, consumerGroup, List.of(recordId));
        } catch (Exception e) {
            // XACK 失败留给 pending 重投（至少一次），重投会产生第二条同 outboxId 回执，Agent 取最新
            log.error("XACK 失败，消息留给 pending 重投。errorCode={}，recordId={}",
                    ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, recordId, e);
        }
    }

    /**
     * 毒消息的 outboxId 尽力提取（取不到给空串，回执仍要发——失败事实不能丢）
     */
    private String parseOutboxIdBestEffort(String envelopeJson) {
        try {
            JsonNode node = json.readTree(envelopeJson);
            return node.path("outboxId").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 本地失败（媒体缺失等）按 errorCode 映射回执失败分类
     */
    private String failureKindOf(ChatBotException e) {
        if (ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED.equals(e.getErrorCode())) {
            return DeliveryFailureKindCodes.MEDIA_MISSING;
        }
        return DeliveryFailureKindCodes.UNKNOWN;
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
