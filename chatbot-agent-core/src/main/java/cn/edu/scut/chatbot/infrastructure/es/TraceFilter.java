package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 链路反查条件（observe-query 契约第 1 节）：按群 / 时间 / 槽位反查 traceId。
 * 全部字段可空——空即不过滤。
 */
public record TraceFilter(String platform,
                          String botId,
                          String groupId,
                          Long fromMillis,
                          Long toMillis,
                          String slotCode) {
}
