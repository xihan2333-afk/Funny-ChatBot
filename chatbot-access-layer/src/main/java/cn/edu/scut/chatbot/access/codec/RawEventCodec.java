package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.envelope.RawEventRef;
import cn.edu.scut.chatbot.contract.in.RawEventPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 兜底原语翻译器（data-model.md 第 4 节）：无法识别的报文收敛为 raw_event 信封。
 *
 * <p>三种触发（illegal_json / unknown_structure / internal_error）统一走本类，
 * 原文均已先留档、rawRef 必填回指留档行；可解析出的标准字段（self_id / group_id / user_id /
 * time）best-effort 照常翻译，解析不出时 botId 用 "unknown" 占位满足信封非空校验。
 *
 * <p>dedupKey：平台对这类报文无原生事件 ID 属常态（research R1），退化为报文内容
 * SHA-256 截断十六进制（≤128 字符）——不自造幂等键的语义在：键完全由报文内容决定，
 * 同一报文重推得到同键，Agent 消费端幂等去重照常可用。
 */
@Component
public class RawEventCodec {

    /**
     * botId 完全不可解析时的占位值
     */
    public static final String UNKNOWN_BOT_ID = "unknown";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SnowflakeIdGenerator idGenerator;

    public RawEventCodec(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    /**
     * @param reason 兜底原因短码，取值见 cn.edu.scut.chatbot.contract.in.RawEventReasonCodes
     */
    public EventEnvelope toEnvelope(String rawJson, String reason, RawEventRef rawRef, long receivedAtMillis) {
        return toEnvelope(rawJson, reason, rawRef, receivedAtMillis, null);
    }

    /**
     * 带管线已解析 dedupKey 的兜底重载：翻译中途失败（internal_error）时键已按消息形态
     * （message_id）解析完成，兜底信封沿用同一键——同一消息「翻译失败后重推成功」的
     * dedupKey 保持稳定，Agent 幂等不因失败路径漂移。
     */
    public EventEnvelope toEnvelope(String rawJson, String reason, RawEventRef rawRef,
                                    long receivedAtMillis, String resolvedDedupKey) {
        JsonNode event = tryParse(rawJson);
        return new EventEnvelope(
                String.valueOf(idGenerator.nextId()),
                null,
                resolvedDedupKey != null ? resolvedDedupKey : contentDedupKey(rawJson),
                PlatformCodes.ONEBOT_V11,
                botIdOrUnknown(event),
                textOrNull(event, "group_id"),
                textOrNull(event, "user_id"),
                PrimitiveTypeCodes.RAW_EVENT,
                new RawEventPayload(reason, rawRef),
                event != null && event.hasNonNull("time") ? event.path("time").asLong() * 1000 : null,
                receivedAtMillis,
                java.util.Map.of(),
                rawRef,
                null);
    }

    private JsonNode tryParse(String rawJson) {
        try {
            return JSON.readTree(rawJson);
        } catch (Exception e) {
            return null;
        }
    }

    private String botIdOrUnknown(JsonNode event) {
        String botId = textOrNull(event, "self_id");
        return botId == null ? UNKNOWN_BOT_ID : botId;
    }

    /**
     * 报文内容 SHA-256 截断十六进制（前 32 个十六进制字符），总长恒 ≤128
     */
    String contentDedupKey(String rawJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // JDK 保证 SHA-256 存在，此分支不可达
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
