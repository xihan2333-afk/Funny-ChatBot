package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;

/**
 * 扩展条目：链接卡片（data-model 第 2 节）。规范化 URL、分享人、层级（卡片/摘要）、
 * 标题站点摘要载荷；默认投影形态：一行卡片文本。
 */
public record LinkCardEntry(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String normalizedUrl,
        String sharerUserId,
        int level,
        String title,
        String siteName,
        String summary
) implements AgentMessage {

    /**
     * 一级卡片
     */
    public static final int LEVEL_CARD = 1;
    /**
     * 二级摘要
     */
    public static final int LEVEL_SUMMARY = 2;

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.LINK_CARD;
    }
}
