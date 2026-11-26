package cn.edu.scut.chatbot.engine.events;

import java.util.List;

/**
 * 引擎事件六组（observability-bridge 契约 2.2 节、research R3）：engine 包关键动作后的
 * 同步通知——intake 登记后、决策出结论后、turn / task 状态机迁移后、Effects 执行后、
 * 回执结算后。一次建设两处消费：观测事件桥（Span 翻译）与管理平台任务状态展示，
 * 都经 {@link EngineEventPublisher} 同一注册出口。
 *
 * <p>发射纪律：同步发射、只做通知（监听器外壳保证快速入队，慢消费不拖累引擎）；
 * 事件是纯值 record，engine 不因事件引入任何对外依赖。traceContext 为 W3C traceparent
 * 原文（信封或触发器行携带——桥解析为 Span 父上下文；内部触发与无追踪环境为 null，
 * 桥对其开新根 trace）。
 */
public sealed interface EngineEvent permits
        EngineEvent.IntakeRegistered,
        EngineEvent.DecisionMade,
        EngineEvent.TurnTransitioned,
        EngineEvent.TaskTransitioned,
        EngineEvent.EffectExecuted,
        EngineEvent.DeliverySettled {

    /**
     * 触发器登记完成（幂等拒插不发射——重复投递没有新事实）
     */
    record IntakeRegistered(
            String platform, String botId, String groupId,
            String dedupKey, String eventType, String traceContext
    ) implements EngineEvent {
    }

    /**
     * 分级决策出结论（L0 规则闸门 / L1 注意力评估 / degrade 保守降级）。
     * accept 时桥在 turn:{turnId} 开长生命周期 turn Span（闭合注册表登记）。
     */
    record DecisionMade(
            String platform, String botId, String groupId,
            long triggerId, Long turnId,
            String decision, String reason, String layer,
            String traceContext
    ) implements EngineEvent {
    }

    /**
     * Turn 状态机迁移（turnId、from → to、发言清单——SETTLING 时刻携带）。
     * 桥凭 turn:{turnId} 在闭合注册表定位在途 turn Span 并按事件推进或闭合。
     */
    record TurnTransitioned(
            String platform, String botId, String groupId,
            long turnId, String fromStatus, String toStatus,
            List<String> speechOutboxIds
    ) implements EngineEvent {
    }

    /**
     * Task 状态机迁移（taskId、from → to、Mandate 类别）
     */
    record TaskTransitioned(
            String platform, String botId, String groupId,
            long taskId, String fromStatus, String toStatus,
            String mandateKind
    ) implements EngineEvent {
    }

    /**
     * Effect 执行结果（kind、outcome：success / retryDeferred / failed / unknownKind、重试次数）
     */
    record EffectExecuted(
            String platform, String botId, String groupId,
            String kind, String outcome, int retryCount
    ) implements EngineEvent {

        public static final String OUTCOME_SUCCESS = "success";
        public static final String OUTCOME_RETRY_DEFERRED = "retryDeferred";
        public static final String OUTCOME_FAILED = "failed";
        public static final String OUTCOME_UNKNOWN_KIND = "unknownKind";
    }

    /**
     * 出事件回执结算（outboxId、回执状态、原出事件 traceId——回执引用）。
     * 平台拒发时 status=failed（US8 拒发回流审计的观测侧事实）。
     * traceContext 为原出事件信封的 traceparent 原文（桥据此把结算 Span 挂回原 trace）。
     */
    record DeliverySettled(
            String platform, String botId, String groupId,
            long outboxId, String settleStatus, String originalTraceId, String traceContext
    ) implements EngineEvent {
    }
}
