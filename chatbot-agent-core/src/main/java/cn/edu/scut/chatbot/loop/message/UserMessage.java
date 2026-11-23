package cn.edu.scut.chatbot.loop.message;

import cn.edu.scut.chatbot.contract.message.MessagePart;

import java.time.Instant;
import java.util.List;

/**
 * 基准条目：群友发言（data-model 第 2 节）。多模态段复用 contract 的 MessagePart
 * （跨进程共享的段类型，非业务包）。
 */
public record UserMessage(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String senderUserId,
        List<MessagePart> parts
) implements AgentMessage {

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.USER;
    }
}
