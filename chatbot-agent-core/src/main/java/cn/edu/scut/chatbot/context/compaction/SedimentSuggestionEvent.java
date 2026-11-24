package cn.edu.scut.chatbot.context.compaction;

/**
 * "该时段历史建议沉淀"提示事件（设计 :1321：压缩完成时由上下文管理发、记忆系统消费）。
 * 是否消费、怎么消费是记忆系统的自由——上下文管理不反向耦合记忆系统的进度。
 *
 * @param platform       群标识
 * @param botId          群标识
 * @param groupId        群标识
 * @param summaryEntryId 本次压缩产出的摘要条目 ID（沉淀取数的起点锚）
 * @param compactedCount 本次压缩收拢的条目数（时段体量的观测口径）
 */
public record SedimentSuggestionEvent(
        String platform,
        String botId,
        String groupId,
        String summaryEntryId,
        int compactedCount
) {
}
