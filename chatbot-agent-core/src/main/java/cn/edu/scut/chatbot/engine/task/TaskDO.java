package cn.edu.scut.chatbot.engine.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * task 表 DO：主动行为状态机（Mandate 内嵌，data-model 第 8 节）。
 */
@Data
public class TaskDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * light 单轮生成 / heavy 多轮推理交 Worker
     */
    private String taskKind;

    /**
     * 行为类别（daily_greeting / topic_follow / research / image / ...）
     */
    private String category;

    /**
     * TaskStatus 短码
     */
    private String status;

    /**
     * admin / member / autonomous
     */
    private String mandateSource;

    private String mandateRef;

    private String creatorUserId;

    /**
     * 任务快照 JSON：请求原文、规格、时效、背景——创建时封存，Worker 唯一输入
     */
    private String snapshot;

    /**
     * 条件唤醒订阅 JSON
     */
    private String wakeCondition;

    private LocalDateTime scheduleAt;

    private LocalDateTime expireAt;

    /**
     * 成功载荷或失败原因 JSON（失败同样是结论）
     */
    private String result;

    private String errorCode;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;

    // ===== 字典值 =====
    public static final String KIND_LIGHT = "light";
    public static final String KIND_HEAVY = "heavy";

    public static final String MANDATE_ADMIN = "admin";
    public static final String MANDATE_MEMBER = "member";
    public static final String MANDATE_AUTONOMOUS = "autonomous";
}
