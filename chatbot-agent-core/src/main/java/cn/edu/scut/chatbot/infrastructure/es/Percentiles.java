package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 分位组（毫秒）：p50 / p90 / p99。样本不足 1 条时对应分位为 null。
 */
public record Percentiles(Long p50, Long p90, Long p99) {
}
