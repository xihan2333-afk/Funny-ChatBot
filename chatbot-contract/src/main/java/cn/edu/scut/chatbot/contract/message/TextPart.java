package cn.edu.scut.chatbot.contract.message;

/**
 * 文本段。OneBot 来源：text.text。
 *
 * @param part 段类型多态标记，恒为 "text"
 * @param text 文本内容，非空
 */
public record TextPart(
        String part,
        String text
) implements MessagePart {

    public static final String TYPE_TEXT = "text";
}
