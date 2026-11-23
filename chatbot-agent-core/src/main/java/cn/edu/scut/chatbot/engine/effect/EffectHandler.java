package cn.edu.scut.chatbot.engine.effect;

/**
 * Effect 处理器契约（契约 engine-commands.md 第 3 节）：把载荷转为对引擎组件或出站的调用。
 * 新增 kind 加处理器类、扫描循环不动。
 *
 * <p>异常二分决定重试策略（EffectsExecutor 实现）：
 * RetryableException → 指数退避；NonRetryableException → 置 failed；其他异常 → 按可重试。
 */
public interface EffectHandler {

    /**
     * 本处理器服务的 kind（与 EffectsQueueDO.KIND_* 一致）
     */
    String kind();

    /**
     * 执行动作载荷（幂等要求：动作可安全重放——出流投递靠 outbox 查重、评估靠 pending 行集）
     */
    void handle(EffectsQueueDO effect) throws Exception;
}
