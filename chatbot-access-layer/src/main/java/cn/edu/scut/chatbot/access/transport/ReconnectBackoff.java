package cn.edu.scut.chatbot.access.transport;

/**
 * 重连退避计算器（契约 onebot-ws-adapter.md 第 1 节）：指数退避 1s 起步、每次 ×multiplier、
 * 上限封顶；重连成功后序列重置。
 *
 * <p>纯计算类、无 IO、无时钟依赖——每次调用 {@link #nextDelayMillis()} 返回当前应等待的
 * 时长并递增失败计数，由调用方（重连循环）自行 sleep 与 reset。固定参数独立可测。
 */
public class ReconnectBackoff {

    private final long initialBackoffMillis;
    private final double multiplier;
    private final long maxBackoffMillis;

    /**
     * 连续失败次数（可供「连上即断 × N 次 = 鉴权失败」的判定复用）
     */
    private int consecutiveFailures;

    public ReconnectBackoff(long initialBackoffMillis, double multiplier, long maxBackoffMillis) {
        this.initialBackoffMillis = initialBackoffMillis;
        this.multiplier = multiplier;
        this.maxBackoffMillis = maxBackoffMillis;
    }

    /**
     * 返回本次重连前应等待的毫秒数，并把失败计数 +1（起步值即与上限取 min——首值也受上限约束）
     */
    public long nextDelayMillis() {
        long delay = Math.min(initialBackoffMillis, maxBackoffMillis);
        for (int i = 0; i < consecutiveFailures; i++) {
            delay = Math.min((long) (delay * multiplier), maxBackoffMillis);
        }
        consecutiveFailures++;
        return delay;
    }

    /**
     * 连接成功后调用：退避序列归零，下次失败从起步值重新计
     */
    public void reset() {
        consecutiveFailures = 0;
    }

    /**
     * 当前连续失败次数
     */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }
}
