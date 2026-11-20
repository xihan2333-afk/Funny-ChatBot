package cn.edu.scut.chatbot.contract.message;

/**
 * @param part         段类型多态标记，恒为 "at"
 * @param targetUserId 被 @ 的用户 QQ 号字符串，或 "all"；非空
 * @某人段。OneBot 来源：at.qq（"all" 表示 @全体成员）。
 */
public record AtPart(
        String part,
        String targetUserId
) implements MessagePart {

    public static final String TYPE_AT = "at";

    /**
     * @全体成员时 targetUserId 的取值
     */
    public static final String TARGET_ALL = "all";
}
