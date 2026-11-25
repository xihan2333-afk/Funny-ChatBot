package cn.edu.scut.chatbot.memory.emotion;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * emotion_log 表 DO（data-model §1.7）：append-only 轻表——每次情绪变更（规则与自报
 * 两通道共用写路径）顺记一行；每日任务裁剪保留 90 天；演进信号月度现查聚合不建中间表。
 */
@Data
public class EmotionLogDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    private Long personaId;

    /**
     * 变更后标签
     */
    private String label;

    /**
     * 变更后原始强度（未衰减）
     */
    private Integer intensityAfter;

    /**
     * 本次变动（正负）
     */
    private Integer delta;

    /**
     * rule / self_report
     */
    private String source;

    private String cause;

    private LocalDateTime occurredAt;

    public static final String SOURCE_RULE = "rule";
    public static final String SOURCE_SELF_REPORT = "self_report";
}
