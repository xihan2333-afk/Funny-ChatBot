package cn.edu.scut.chatbot.contract.message;

/**
 * 系统表情段。OneBot 来源：face.id。
 *
 * @param part   段类型多态标记，恒为 "face"
 * @param faceId 表情编号字符串
 */
public record FacePart(
        String part,
        String faceId
) implements MessagePart {

    public static final String TYPE_FACE = "face";
}
