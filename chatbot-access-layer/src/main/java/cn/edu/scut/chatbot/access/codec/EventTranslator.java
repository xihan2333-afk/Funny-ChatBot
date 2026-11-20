package cn.edu.scut.chatbot.access.codec;

import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator;
import cn.edu.scut.chatbot.access.bootstrap.TraceContextHelper;
import cn.edu.scut.chatbot.access.mediastore.MediaTransferService;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.RawEventRef;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.in.RawEventReasonCodes;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * 事件翻译统一入口（data-model.md 第 4 节映射总表的执行侧）：把已解析的 OneBot 事件
 * 分发到对应原语 Codec——映射总表逐行有契约测试守（EventMappingContractTest）。
 *
 * <p>分发顺序即归属判定：群消息 → 互动 → 成员 → Bot → 群事件；未建模结构
 * （私聊第一版、gray_tip 等扩展、未知事件）一律收敛 raw_event 保真；翻译内部异常
 * 同样兜底 internal_error——原文均已先留档，链路可回溯。
 *
 * <p>群消息在返回前完成媒体段有界同步转存（事件一次成型）与信封校验。
 */
@Component
public class EventTranslator {

    private static final Logger log = LoggerFactory.getLogger(EventTranslator.class);

    private final GroupMessageCodec groupMessageCodec;
    private final InteractionCodec interactionCodec;
    private final MemberEventCodec memberEventCodec;
    private final BotEventCodec botEventCodec;
    private final GroupEventCodec groupEventCodec;
    private final RawEventCodec rawEventCodec;
    private final DedupKeyResolver dedupKeyResolver;
    private final MediaTransferService mediaTransferService;
    private final EnvelopeValidator validator;
    private final SnowflakeIdGenerator idGenerator;
    private final TraceContextHelper traceContextHelper;
    private final Clock clock;

    /**
     * 显式开 Span 用（R6 决策：不依赖 javaagent 对 WS 入口的自动埋点；无 agent 时为 no-op）
     */
    private final Tracer tracer = GlobalOpenTelemetry.get().getTracer("chatbot-access-layer");

    public EventTranslator(GroupMessageCodec groupMessageCodec,
                           InteractionCodec interactionCodec,
                           MemberEventCodec memberEventCodec,
                           BotEventCodec botEventCodec,
                           GroupEventCodec groupEventCodec,
                           RawEventCodec rawEventCodec,
                           DedupKeyResolver dedupKeyResolver,
                           MediaTransferService mediaTransferService,
                           EnvelopeValidator validator,
                           SnowflakeIdGenerator idGenerator,
                           TraceContextHelper traceContextHelper,
                           Clock clock) {
        this.groupMessageCodec = groupMessageCodec;
        this.interactionCodec = interactionCodec;
        this.memberEventCodec = memberEventCodec;
        this.botEventCodec = botEventCodec;
        this.groupEventCodec = groupEventCodec;
        this.rawEventCodec = rawEventCodec;
        this.dedupKeyResolver = dedupKeyResolver;
        this.mediaTransferService = mediaTransferService;
        this.validator = validator;
        this.idGenerator = idGenerator;
        this.traceContextHelper = traceContextHelper;
        this.clock = clock;
    }

    /**
     * 翻译一条已解析的平台事件（meta_event 已由上游消化，不会到达这里）。
     * 本方法不抛异常——一切失败收敛为 raw_event 兜底信封。
     *
     * <p>翻译全程包裹在显式开启的 platform.event.receive Span 内（R6 决策），
     * 完成后经 W3C propagator 注入 traceparent 写入信封 traceContext——
     * 无 javaagent 的环境 Span 为 no-op，traceparent 为全零合法格式，不阻塞主流程。
     */
    public EventEnvelope translate(JsonNode event, String rawFrame, RawEventRef rawRef, long receivedAtMillis) {
        Span receiveSpan = tracer.spanBuilder("platform.event.receive")
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();
        try (Scope ignored = receiveSpan.makeCurrent()) {
            // dedupKey 在翻译尝试前解析：翻译中途失败（internal_error 兜底）沿用同一键，
            // 同一消息失败后重推的 dedupKey 保持稳定（Agent 幂等不因失败路径漂移）
            String dedupKey = dedupKeyResolver.dedupKeyOf(event, rawFrame);
            EventEnvelope envelope;
            try {
                EventEnvelope dispatched = dispatch(event, rawFrame, rawRef, receivedAtMillis, dedupKey);
                envelope = dispatched == null
                        ? rawEventCodec.toEnvelope(rawFrame, RawEventReasonCodes.UNKNOWN_STRUCTURE,
                        rawRef, receivedAtMillis, dedupKey)
                        : dispatched;
            } catch (Exception e) {
                log.error("事件翻译内部异常，收敛 raw_event 兜底。errorCode={}，留档 rawEventId={}",
                        ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                        rawRef == null ? "?" : rawRef.rawEventId(), e);
                envelope = rawEventCodec.toEnvelope(
                        rawFrame, RawEventReasonCodes.INTERNAL_ERROR, rawRef, receivedAtMillis, dedupKey);
            }
            // 注：无 javaagent 时注入结果为 null（no-op propagator 不写 carrier），信封 traceContext 为 null；
            // 挂载 agent 后为真实 W3C traceparent
            return withTraceContext(envelope, traceContextHelper.injectCurrent(Context.current()));
        } finally {
            receiveSpan.end();
        }
    }

    /**
     * 信封写入 traceContext（W3C traceparent，Agent 消费端可提取续接）
     */
    private EventEnvelope withTraceContext(EventEnvelope envelope, String traceContext) {
        if (traceContext == null) {
            return envelope;
        }
        return new EventEnvelope(envelope.eventId(), envelope.outboxId(), envelope.dedupKey(),
                envelope.platform(), envelope.botId(), envelope.groupId(), envelope.userId(),
                envelope.type(), envelope.payload(), envelope.occurredAt(), envelope.receivedAt(),
                envelope.extension(), envelope.rawRef(), traceContext);
    }

    /**
     * 映射总表分发：返回 null 表示结构未建模（由兜底语义收敛 raw_event）
     */
    private EventEnvelope dispatch(JsonNode event, String rawFrame, RawEventRef rawRef,
                                   long receivedAtMillis, String dedupKey) {
        String eventId = String.valueOf(idGenerator.nextId());

        String postType = event.path("post_type").asText();
        if ("message".equals(postType) || "message_sent".equals(postType)) {
            if ("group".equals(event.path("message_type").asText())) {
                return translateGroupMessage(event, rawFrame, rawRef, receivedAtMillis, eventId, dedupKey);
            }
            // 私聊第一版预留：结构认识但功能未开放，走兜底保真
            return null;
        }
        if ("notice".equals(postType) || "request".equals(postType)) {
            EventEnvelope envelope;
            envelope = interactionCodec.toEnvelope(event, rawFrame, rawRef, receivedAtMillis, eventId, dedupKey);
            if (envelope == null) {
                envelope = memberEventCodec.toEnvelope(event, rawFrame, rawRef, receivedAtMillis, eventId, dedupKey);
            }
            if (envelope == null) {
                envelope = botEventCodec.toEnvelope(event, rawFrame, rawRef, receivedAtMillis, eventId, dedupKey);
            }
            if (envelope == null) {
                envelope = groupEventCodec.toEnvelope(event, rawFrame, rawRef, receivedAtMillis, eventId, dedupKey);
            }
            if (envelope != null) {
                validator.validateInbound(envelope);
            }
            return envelope;
        }
        return null;
    }

    /**
     * 群消息：翻译 → 媒体转存尝试（段替换）→ 校验——事件一次成型
     */
    private EventEnvelope translateGroupMessage(JsonNode event, String rawFrame, RawEventRef rawRef,
                                                long receivedAtMillis, String eventId, String dedupKey) {
        EventEnvelope envelope = groupMessageCodec.toEnvelope(event, receivedAtMillis, eventId);
        envelope = withKeys(envelope, dedupKey, rawRef);

        GroupMessagePayload payload = (GroupMessagePayload) envelope.payload();
        List<MessagePart> transferredParts = mediaTransferService.transferInbound(payload.parts());
        EventEnvelope transferred = new EventEnvelope(
                envelope.eventId(), envelope.outboxId(), dedupKey, envelope.platform(),
                envelope.botId(), envelope.groupId(), envelope.userId(), envelope.type(),
                new GroupMessagePayload(payload.messageId(), payload.subType(), payload.sender(),
                        transferredParts, payload.rawText()),
                envelope.occurredAt(), envelope.receivedAt(), envelope.extension(),
                rawRef, envelope.traceContext());

        validator.validateInbound(transferred);
        return transferred;
    }

    /**
     * 补齐 dedupKey 与 rawRef（GroupMessageCodec 不感知管线侧的键解析与留档引用）
     */
    private EventEnvelope withKeys(EventEnvelope envelope, String dedupKey, RawEventRef rawRef) {
        return new EventEnvelope(envelope.eventId(), envelope.outboxId(), dedupKey,
                envelope.platform(), envelope.botId(), envelope.groupId(), envelope.userId(),
                envelope.type(), envelope.payload(), envelope.occurredAt(), envelope.receivedAt(),
                envelope.extension(), rawRef, envelope.traceContext());
    }
}
