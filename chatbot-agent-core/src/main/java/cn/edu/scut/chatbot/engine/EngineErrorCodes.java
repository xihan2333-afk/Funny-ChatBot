package cn.edu.scut.chatbot.engine;

/**
 * 引擎域统一错误码常量：点分字符串短码，engine.* 命名空间（宪法「异常与错误码」节）。
 *
 * <p>使用纪律：错误码只描述「系统哪里坏了」，不承载业务分支；业务拒绝走
 * CommandRejectedException 的 reason；ERROR 级日志必带 errorCode 与 traceId。
 */
public final class EngineErrorCodes {

    // ===== turn（被动回应状态机） =====
    /**
     * 持久化状态与迁移请求矛盾（如 DB 里 SETTLED 却收到推进事实）——数据损坏信号
     */
    public static final String TURN_STATE_CONFLICT = "engine.turn.state_conflict";
    /**
     * Turn 状态机遇到未知迁移目标（枚举外的值，代码 bug）
     */
    public static final String TURN_UNKNOWN_STATE = "engine.turn.unknown_state";
    /**
     * 生成失败（StreamFn 返回 Failed 或重试耗尽——事实登记，进 turn.error_code）
     */
    public static final String TURN_GENERATION_FAILED = "engine.turn.generation_failed";

    // ===== task（主动行为状态机） =====
    /**
     * Task 状态机事实矛盾
     */
    public static final String TASK_STATE_CONFLICT = "engine.task.state_conflict";
    /**
     * Task 快照损坏（反序列化失败等，Worker 无法启动）
     */
    public static final String TASK_SNAPSHOT_BROKEN = "engine.task.snapshot_broken";

    // ===== intake（入流登记） =====
    /**
     * 触发器登记写入失败
     */
    public static final String INTAKE_REGISTER_FAILED = "engine.intake.register_failed";
    /**
     * 信封结构坏（入流毒消息，按不可重试跳过）
     */
    public static final String INTAKE_POISON_ENVELOPE = "engine.intake.poison_envelope";

    // ===== session（会话与推理权） =====
    /**
     * 会话元数据读写失败
     */
    public static final String SESSION_STORE_FAILED = "engine.session.store_failed";

    // ===== outbox（出站闭环） =====
    /**
     * 出事件落库失败（未落库绝不投递——先落库纪律）
     */
    public static final String OUTBOX_PERSIST_FAILED = "engine.outbox.persist_failed";
    /**
     * 出流 XADD 失败（行已在 outbox，dispatch Effect 退避重试）
     */
    public static final String OUTBOX_DISPATCH_FAILED = "engine.outbox.dispatch_failed";
    /**
     * 回执关联到不存在的 outbox 行（数据不一致）
     */
    public static final String OUTBOX_REPORT_ORPHAN = "engine.outbox.report_orphan";

    // ===== lifecycle（群生命周期） =====
    /**
     * 群生命周期状态矛盾
     */
    public static final String LIFECYCLE_STATE_CONFLICT = "engine.lifecycle.state_conflict";

    // ===== gate（分级闸门） =====
    /**
     * L1 评估调用失败（退避重试中；耗尽走保守 ignore 降级，不抛到顶层）
     */
    public static final String GATE_L1_CALL_FAILED = "engine.gate.l1_call_failed";

    // ===== id（发号器） =====
    /**
     * 雪花发号器时钟回拨超过容忍阈值，拒绝发号（宁可失败不可重复）
     */
    public static final String ID_CLOCK_BACKWARD = "engine.id.clock_backward";

    // ===== stream（Redis 流） =====
    /**
     * Redis 流读写失败（瞬时故障可重试；入方向不确认等 pending 重投）
     */
    public static final String STREAM_IO_FAILED = "engine.stream.io_failed";

    private EngineErrorCodes() {
    }
}
