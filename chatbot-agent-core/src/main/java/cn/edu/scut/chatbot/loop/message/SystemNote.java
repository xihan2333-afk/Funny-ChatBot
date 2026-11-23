package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;

/**
 * 扩展条目：系统现注（data-model 第 2 节）。注入来源与提示文本（如「该群已静默两小时」），
 * 按注入目的翻译为 user 角色提示。
 */
public record SystemNote(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String injectionSource,
        String promptText
) implements AgentMessage {

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.SYSTEM_NOTE;
    }
}
