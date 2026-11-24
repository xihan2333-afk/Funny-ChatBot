package cn.edu.scut.chatbot.engine.effect;

/**
 * Effects 队列域错误码：effect.* 命名空间。只描述「哪个动作坏了」，重试策略由
 * EffectsExecutor 按异常二分决定（Retryable 退避、NonRetryable 置 failed）。
 */
public final class EffectErrorCodes {

    /**
     * Effect 载荷反序列化失败（行数据损坏，不可重试）
     */
    public static final String PAYLOAD_BROKEN = "effect.payload_broken";

    /**
     * 未知 Effect kind（处理器注册表查无——代码版本与数据不一致）
     */
    public static final String UNKNOWN_KIND = "effect.unknown_kind";

    /**
     * Effect 重试超限置 failed（动作持续失败，需要人工介入；告警必发）
     */
    public static final String RETRY_EXCEEDED = "effect.retry_exceeded";

    /**
     * Effect 执行启动恢复失败（running 批量置回 pending 的 DB 操作失败）
     */
    public static final String RECOVERY_FAILED = "effect.recovery_failed";

    private EffectErrorCodes() {
    }
}
