package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 单条日志视图（日志页消费）：时间、级别、logger、消息与链路 / 群标识。
 */
public record LogEntry(long timestampMillis,
                       String level,
                       String logger,
                       String message,
                       String traceId,
                       String spanId,
                       String groupId,
                       String turnId,
                       String category) {
}
