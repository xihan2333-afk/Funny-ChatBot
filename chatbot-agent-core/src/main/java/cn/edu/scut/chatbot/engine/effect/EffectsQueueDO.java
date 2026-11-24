package cn.edu.scut.chatbot.engine.effect;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * effects_queue 表 DO：持久化副作用队列（data-model 第 10 节）。
 * 事务内写入、提交后执行、同 key 合并（uk_merge）、成功删行、失败退避、重启续跑。
 */
@Data
public class EffectsQueueDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * assessment / generation / dispatch / taskWake / workerLaunch
     */
    private String kind;

    /**
     * 动作载荷 JSON（如 generation 携带 turnId）
     */
    private String payload;

    /**
     * pending / running / failed
     */
    private String status;

    private Integer retryCount;

    private LocalDateTime nextRunAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ===== kind 短码 =====
    public static final String KIND_ASSESSMENT = "assessment";
    public static final String KIND_GENERATION = "generation";
    public static final String KIND_DISPATCH = "dispatch";
    public static final String KIND_TASK_WAKE = "taskWake";
    public static final String KIND_WORKER_LAUNCH = "workerLaunch";

    // ===== status 短码 =====
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_FAILED = "failed";
}
