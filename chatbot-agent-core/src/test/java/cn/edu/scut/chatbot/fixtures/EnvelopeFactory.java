package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.BotEventPayload;
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.in.SenderProfile;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试信封工厂（research R9：agent-core 端到端测试直接 XADD 信封 JSON，不用假 NapCat）。
 *
 * <p>各形态信封基于 chatbot-contract 主代码 record 构造（test 域依赖 contract 本就合法）；
 * 测试域自持薄工厂、不做跨模块 test-jar（两端各自独立演进的既定取舍）。序列化走 record
 * 直出 Jackson——线格式即字段名平铺，与接入层 EnvelopeCodec 的序列化形态一致。
 */
public final class EnvelopeFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 平台消息 ID 自增源：每条群消息默认拿到不同 dedupKey（幂等用例显式传同值考重复）
     */
    private static final AtomicLong MESSAGE_ID_SEQ = new AtomicLong(90000);

    /**
     * 测试默认三元组（单群剧本的口径；多群用例用带参方法覆盖）
     */
    public static final String DEFAULT_PLATFORM = "qq";
    public static final String DEFAULT_BOT_ID = "10000";
    public static final String DEFAULT_GROUP_ID = "80001";

    private EnvelopeFactory() {
    }

    /**
     * 普通群消息（无 @）：userId 发言人、text 文本
     */
    public static EventEnvelope groupMessage(String userId, String text) {
        return groupMessage(DEFAULT_PLATFORM, DEFAULT_BOT_ID, DEFAULT_GROUP_ID, userId, text, null);
    }

    /**
     * 群消息（可带 @bot 的 AtPart）：atBot=true 时文本前置 @ 段
     */
    public static EventEnvelope groupMessage(String userId, String text, boolean atBot) {
        return groupMessage(DEFAULT_PLATFORM, DEFAULT_BOT_ID, DEFAULT_GROUP_ID, userId, text, null, atBot);
    }

    /**
     * 群消息（全参形态）：dedupKey 显式传入考幂等，occurredAt 显式传入考时效
     */
    public static EventEnvelope groupMessage(String platform, String botId, String groupId,
                                             String userId, String text, Long occurredAt) {
        return groupMessage(platform, botId, groupId, userId, text, occurredAt, false);
    }

    public static EventEnvelope groupMessage(String platform, String botId, String groupId,
                                             String userId, String text, Long occurredAt, boolean atBot) {
        List<MessagePart> parts = new ArrayList<>();
        if (atBot) {
            parts.add(new AtPart(AtPart.TYPE_AT, botId));
        }
        parts.add(new TextPart(TextPart.TYPE_TEXT, text));
        String messageId = "msg-" + MESSAGE_ID_SEQ.incrementAndGet();
        return envelope(platform, botId, groupId, userId, PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload(messageId, "normal", sender(userId), parts, text),
                messageId, occurredAt);
    }

    /**
     * Bot 自身事件（考 L0「Bot 自身消息一律忽略」与生命周期联动）
     */
    public static EventEnvelope botEvent(String subType) {
        return botEvent(DEFAULT_PLATFORM, DEFAULT_BOT_ID, DEFAULT_GROUP_ID, subType);
    }

    public static EventEnvelope botEvent(String platform, String botId, String groupId, String subType) {
        String dedupKey = "bot-evt-" + MESSAGE_ID_SEQ.incrementAndGet();
        return envelope(platform, botId, groupId, null, PrimitiveTypeCodes.BOT_EVENT,
                new BotEventPayload(subType, "90001", null, groupId), dedupKey, null);
    }

    /**
     * 投递回执（成功形态）：按 outboxId 关联结算
     */
    public static EventEnvelope deliveryReportSettled(String outboxId, String platformMessageId) {
        return deliveryReport(DEFAULT_PLATFORM, DEFAULT_BOT_ID, outboxId, "success", null, platformMessageId);
    }

    /**
     * 投递回执（失败形态）：failureKind 走不可重试分类（如 forbidden）
     */
    public static EventEnvelope deliveryReportFailed(String outboxId, String failureKind) {
        return deliveryReport(DEFAULT_PLATFORM, DEFAULT_BOT_ID, outboxId, "failed", failureKind, null);
    }

    public static EventEnvelope deliveryReport(String platform, String botId, String outboxId,
                                               String status, String failureKind, String platformMessageId) {
        return new EventEnvelope(
                "evt-report-" + MESSAGE_ID_SEQ.incrementAndGet(), null, "report-" + outboxId,
                platform, botId, DEFAULT_GROUP_ID, null,
                PrimitiveTypeCodes.DELIVERY_REPORT,
                new DeliveryReportPayload(outboxId, platformMessageId, status, failureKind,
                        null, null, System.currentTimeMillis(), false),
                System.currentTimeMillis(), System.currentTimeMillis(),
                Map.of(), null, null);
    }

    /**
     * 覆盖信封 dedupKey（幂等用例：两封不同消息共用同一幂等键考重复投递）
     */
    public static EventEnvelope withDedup(EventEnvelope envelope, String dedupKey) {
        return new EventEnvelope(envelope.eventId(), envelope.outboxId(), dedupKey,
                envelope.platform(), envelope.botId(), envelope.groupId(), envelope.userId(),
                envelope.type(), envelope.payload(), envelope.occurredAt(), envelope.receivedAt(),
                envelope.extension(), envelope.rawRef(), envelope.traceContext());
    }

    /**
     * 覆盖信封 traceContext（跨进程接续用例：模拟接入层带 W3C traceparent 的信封）
     */
    public static EventEnvelope withTraceContext(EventEnvelope envelope, String traceContext) {
        return new EventEnvelope(envelope.eventId(), envelope.outboxId(), envelope.dedupKey(),
                envelope.platform(), envelope.botId(), envelope.groupId(), envelope.userId(),
                envelope.type(), envelope.payload(), envelope.occurredAt(), envelope.receivedAt(),
                envelope.extension(), envelope.rawRef(), traceContext);
    }

    /**
     * 信封 → 线格式 JSON（XADD 的 envelope 字段值）
     */
    public static String toJson(EventEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测试信封序列化失败（构造数据 bug）", e);
        }
    }

    private static EventEnvelope envelope(String platform, String botId, String groupId, String userId,
                                          String type, cn.edu.scut.chatbot.contract.envelope.EnvelopePayload payload,
                                          String dedupKey, Long occurredAt) {
        long now = System.currentTimeMillis();
        return new EventEnvelope(
                "evt-" + MESSAGE_ID_SEQ.incrementAndGet(), null, dedupKey,
                platform, botId, groupId, userId, type, payload,
                occurredAt != null ? occurredAt : now, now,
                Map.of(), null, null);
    }

    private static SenderProfile sender(String userId) {
        return new SenderProfile(userId, "测试用户" + userId, "", "member", "1", "");
    }
}
