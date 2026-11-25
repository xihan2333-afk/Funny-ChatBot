package cn.edu.scut.chatbot.memory.emotion;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 运行时情绪状态（data-model §4.1，agent_session.emotion JSON 的强类型形态）：
 * 可推导暂态——label 须在人格情绪词汇集内、intensity 0~100、cause 起因一句话、
 * updatedAt 懒衰减计算基准、baseLabel 人格基线标签快照（回归目标）。
 *
 * <p>JSON 兼容既有三字段（002 桩期的 label/intensity/cause）：旧数据缺
 * updatedAt/baseLabel 时按 null 读入——updatedAt 为 null 视为"很久以前"，
 * 懒衰减自然回基线（崩溃恢复"重启后心情平复"的同源机制）。
 */
public record EmotionState(String label, int intensity, String cause,
                           LocalDateTime updatedAt, String baseLabel) {

    /**
     * 基线情绪（会话无情绪记录 / 情绪回落到阈值下时的形态）
     */
    public static EmotionState baseline(String baseLabel, LocalDateTime now) {
        return new EmotionState(baseLabel, 50, null, now, baseLabel);
    }

    /**
     * 自 JSON 反序列化（null / 空串 / 坏 JSON 回 null——调用方按无记录处理走人格基线）
     */
    public static EmotionState fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(json);
            LocalDateTime updatedAt = node.hasNonNull("updatedAt")
                    ? LocalDateTime.parse(node.path("updatedAt").asText()) : null;
            return new EmotionState(
                    node.path("label").asText("平静"),
                    node.path("intensity").asInt(50),
                    node.hasNonNull("cause") ? node.path("cause").asText() : null,
                    updatedAt,
                    node.hasNonNull("baseLabel") ? node.path("baseLabel").asText() : null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 序列化为 emotion 列 JSON（五字段全量——updatedAt 是下次懒衰减的基准）
     */
    public String toJson() {
        return "{\"label\":\"" + escape(label) + "\",\"intensity\":" + intensity
                + ",\"cause\":" + (cause == null ? "null" : "\"" + escape(cause) + "\"")
                + ",\"updatedAt\":" + (updatedAt == null ? "null" : "\"" + updatedAt + "\"")
                + ",\"baseLabel\":" + (baseLabel == null ? "null" : "\"" + escape(baseLabel) + "\"")
                + "}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
