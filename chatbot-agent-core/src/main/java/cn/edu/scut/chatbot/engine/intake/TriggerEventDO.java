package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * trigger_event 表 DO：触发器登记与决策留档合一（data-model 第 6 节）——
 * 一行 = 一条触发器的完整生命周期（登记即写、决策回填）。
 */
@Data
public class TriggerEventDO {

    /**
     * 雪花 ID
     */
    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * external 平台事件 / internal 调度器内部触发
     */
    private String triggerKind;

    /**
     * 入事件原语类型或内部触发类别（如 silenceBreak、taskDelivery）
     */
    private String eventType;

    /**
     * 幂等键：external 取平台事件 ID、internal 取业务键
     */
    private String dedupKey;

    /**
     * 触发内容摘要
     */
    private String digest;

    /**
     * 关联来源（raw_event_log.id、taskId）
     */
    private String sourceRef;

    /**
     * pending / accept / ignore
     */
    private String decision;

    /**
     * L0 / L1 / degrade（评估失败保守忽略）
     */
    private String decisionLayer;

    /**
     * 决策理由（数据飞轮原料）
     */
    private String decisionReason;

    /**
     * L1 批次摘要 JSON
     */
    private String batchDigest;

    /**
     * accept 后归属的 Turn
     */
    private Long turnId;

    /**
     * 事件时间（话题新鲜度判定输入）
     */
    private LocalDateTime occurredAt;

    /**
     * 决策时间
     */
    private LocalDateTime decidedAt;

    /**
     * 入事件信封的 W3C traceparent 原文（跨进程接续：gate / turn / outbox 侧凭它把
     * 后续 Span 挂到接入层同一条 trace 上；内部触发为空）
     */
    private String traceContext;

    // ===== 字典值常量 =====
    public static final String KIND_EXTERNAL = "external";
    public static final String KIND_INTERNAL = "internal";
    public static final String DECISION_PENDING = "pending";
    public static final String DECISION_ACCEPT = "accept";
    public static final String DECISION_IGNORE = "ignore";
    public static final String LAYER_L0 = "L0";
    public static final String LAYER_L1 = "L1";
    public static final String LAYER_DEGRADE = "degrade";
}
