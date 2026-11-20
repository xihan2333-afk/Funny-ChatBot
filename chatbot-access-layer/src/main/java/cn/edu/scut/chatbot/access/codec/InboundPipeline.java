package cn.edu.scut.chatbot.access.codec;

import cn.edu.scut.chatbot.access.archive.RawEventArchiver;
import cn.edu.scut.chatbot.access.infrastructure.StreamGateway;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.RawEventRef;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.in.PrivateMessagePayload;
import cn.edu.scut.chatbot.contract.in.RawEventReasonCodes;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 入站主流程编排（data-model.md 第 8 节事务边界）。顺序硬编码、不可调换：
 *
 * <pre>
 * 原文留档（翻译之前——翻译崩了原文已在）
 *   → EventTranslator 翻译（映射总表分发，未建模结构收敛 raw_event 保真）
 *   → XADD 落流
 * </pre>
 *
 * <p>XADD 成功才对平台侧算接收成功（WS 形态表现为连接存活不重推）；Redis 失败时该事件
 * 不确认且原文已留档——错误记日志（errorCode 进告警体系），不让单条事件失败中断消费循环。
 *
 * <p>保真优先于进流：留档失败时该事件直接丢弃（无原文的兜底信封违反 rawRef 必填契约，
 * 且丢失了排障回溯的根）。
 *
 * <p>meta_event（心跳与 lifecycle）在 Transport 层消化不进信封，这里只留档不翻译。
 */
@Component
public class InboundPipeline {

    private static final Logger log = LoggerFactory.getLogger(InboundPipeline.class);

    private final RawEventArchiver archiver;
    private final EventTranslator eventTranslator;
    private final RawEventCodec rawEventCodec;
    private final EnvelopeCodec envelopeCodec;
    private final StreamGateway streamGateway;
    private final Clock clock;
    private final String inStreamKey;

    public InboundPipeline(RawEventArchiver archiver,
                           EventTranslator eventTranslator,
                           RawEventCodec rawEventCodec,
                           EnvelopeCodec envelopeCodec,
                           StreamGateway streamGateway,
                           Clock clock,
                           @Value("${access.stream.in-key}") String inStreamKey) {
        this.archiver = archiver;
        this.eventTranslator = eventTranslator;
        this.rawEventCodec = rawEventCodec;
        this.envelopeCodec = envelopeCodec;
        this.streamGateway = streamGateway;
        this.clock = clock;
        this.inStreamKey = inStreamKey;
    }

    /**
     * 帧分发器入口一：JSON 解析失败的帧——留档后直接走兜底原语（illegal_json）
     */
    public void handleUnparseableFrame(String rawFrame) {
        RawEventRef rawRef = archiveOrDrop(rawFrame, null, null, null, null);
        if (rawRef == null) {
            return;
        }
        publish(rawEventCodec.toEnvelope(rawFrame, RawEventReasonCodes.ILLEGAL_JSON, rawRef, clock.millis()));
    }

    /**
     * 帧分发器入口二：无主 API 响应（echo 不命中待匹配表）——只留档，不产信封
     */
    public void handleOrphanApiResponse(String rawFrame) {
        archiveOrDrop(rawFrame, null, null, null, null);
    }

    /**
     * 帧分发器入口三：平台事件（不含 echo 的帧）。node 是 dispatcher 已解析的结果，避免二次解析
     */
    public void handlePlatformEvent(String rawFrame, JsonNode node) {
        String postType = node.path("post_type").asText("");
        String botId = textOrNull(node, "self_id");
        Long occurredAt = node.hasNonNull("time") ? node.path("time").asLong() * 1000 : null;

        RawEventRef rawRef = archiveOrDrop(rawFrame, resolveEventType(node), null, occurredAt, botId);
        if (rawRef == null) {
            return;
        }

        // meta_event（心跳与 lifecycle）在 Transport 消化不进信封：留档已做，到此为止
        if ("meta_event".equals(postType)) {
            return;
        }

        publish(eventTranslator.translate(node, rawFrame, rawRef, clock.millis()));
    }

    /**
     * XADD 落流；Redis 失败不确认（原文已留档），错误日志带 errorCode 进告警。
     * 成功路径打一条 INFO 摘要（观测用：每条事件一行，凭它看「平台消息到了哪一步」）。
     */
    private void publish(EventEnvelope envelope) {
        String json = envelopeCodec.serialize(envelope);
        try {
            streamGateway.appendEnvelope(inStreamKey, json);
            log.info("事件入流：eventId={}，type={}，group={}，user={}，dedupKey={}，内容={}",
                    envelope.eventId(), envelope.type(), envelope.groupId(), envelope.userId(),
                    envelope.dedupKey(), messageTextOf(envelope));
        } catch (Exception e) {
            // StreamGateway 失败统一按写失败处理（无论底层抛 Retryable 还是 Spring DataAccessException）
            log.error("事件 XADD 失败，原文已留档、该事件不确认。errorCode={}，eventId={}，type={}",
                    ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, envelope.eventId(), envelope.type(), e);
        }
    }

    /**
     * 入站日志的消息内容：消息类事件打 rawText 摘要，非消息事件（入群 / 戳一戳等）无文本概念记 "-"
     */
    private String messageTextOf(EventEnvelope envelope) {
        if (envelope.payload() instanceof GroupMessagePayload group) {
            return MessageTextSummary.ofRawText(group.rawText());
        }
        if (envelope.payload() instanceof PrivateMessagePayload priv) {
            return MessageTextSummary.ofRawText(priv.rawText());
        }
        return "-";
    }

    /**
     * 留档写入：失败时记 ERROR 并返回 null——调用方见 null 即放弃本条事件
     * （保真优先于进流，无原文的信封违反 rawRef 契约）。
     */
    private RawEventRef archiveOrDrop(String rawFrame, String eventType, String dedupKey,
                                      Long occurredAt, String botId) {
        try {
            return archiver.archive(
                    PlatformCodes.ONEBOT_V11,
                    botId == null ? RawEventCodec.UNKNOWN_BOT_ID : botId,
                    RawEventArchiver.DIRECTION_IN, eventType, dedupKey, occurredAt, rawFrame);
        } catch (Exception e) {
            log.error("原始报文留档失败，本条事件丢弃。errorCode={}",
                    ErrorCodes.ADAPTER_ARCHIVE_WRITE_FAILED, e);
            return null;
        }
    }

    /**
     * 平台事件类型（留档表 event_type 列）：post_type 加二级类型，解析不可得返回 null
     */
    private String resolveEventType(JsonNode node) {
        String postType = node.path("post_type").asText("");
        if (postType.isEmpty()) {
            return null;
        }
        return switch (postType) {
            case "message", "message_sent" -> postType + "." + node.path("message_type").asText("");
            case "notice" -> postType + "." + node.path("notice_type").asText("");
            case "request" -> postType + "." + node.path("request_type").asText("");
            case "meta_event" -> postType + "." + node.path("meta_event_type").asText("");
            default -> postType;
        };
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
