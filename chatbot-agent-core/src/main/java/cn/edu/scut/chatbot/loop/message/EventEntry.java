package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;
import java.util.Map;

/**
 * 扩展条目：群内事件（互动/成员变动/群事件/Bot 事件，data-model 第 2 节）。
 * 默认投影形态：一行文本、user 角色；成员变动窗口紧张时最先淘汰（weight 默认 10）。
 */
public record EventEntry(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String eventCategory,
        Map<String, String> attributes,
        String summaryLine
) implements AgentMessage {

    /**
     * 成员变动（入群/退群/被踢/禁言）——weight 淘汰次序表最先出窗
     */
    public static final String CATEGORY_MEMBER_CHANGE = "memberChange";
    public static final int WEIGHT_MEMBER_CHANGE = 10;

    /**
     * 互动事件（戳一戳/表情回应等）
     */
    public static final String CATEGORY_INTERACTION = "interaction";
    public static final int WEIGHT_INTERACTION = 20;

    /**
     * 群维度事件（群信息变更等）
     */
    public static final String CATEGORY_GROUP_CHANGE = "groupChange";

    /**
     * Bot 自身事件（被禁言/掉线等——对人格是"与自己有关的事"）
     */
    public static final String CATEGORY_BOT_EVENT = "botEvent";

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.EVENT;
    }
}
