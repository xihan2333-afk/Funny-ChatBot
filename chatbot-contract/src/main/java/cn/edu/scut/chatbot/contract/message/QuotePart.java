package cn.edu.scut.chatbot.contract.message;

/**
 * 引用回复段。OneBot 来源：reply.id。
 *
 * @param part                段类型多态标记，恒为 "quote"
 * @param referencedMessageId 被引用消息的平台 message_id 字符串
 */
public record QuotePart(
        String part,
        String referencedMessageId
) implements MessagePart {

    public static final String TYPE_QUOTE = "quote";
}
