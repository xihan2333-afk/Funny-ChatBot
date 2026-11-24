package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;

/**
 * 扩展条目：压缩摘要（data-model 第 2 节）。社交事实五段结构正文、覆盖的源条目范围；
 * 摘要区唯一投放者，头部自带「[机器摘要，非原文]」声明。
 */
public record SummaryEntry(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String summaryText,
        String coveredRange
) implements AgentMessage {

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.SUMMARY;
    }

    /**
     * 投影时的固定头声明（数据不是原文的显式标记）
     */
    public static final String PROJECTION_HEADER = "[机器摘要，非原文] ";
}
