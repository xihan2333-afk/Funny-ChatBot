package cn.edu.scut.chatbot.access.codec;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 归属判定表（data-model.md 第 4 节）：OneBot 事件以「受影响者是谁」归属——
 * self_id 出现在 user_id / target_id（受影响者是 bot 自己）→ BotEvent；
 * 否则（受影响者是普通成员）→ MemberEvent。判定表独立可测、被两个 codec 共用。
 */
public final class EventOwnership {

    private EventOwnership() {
    }

    /**
     * group_ban 的归属：user_id 与 self_id 相同 → 受影响者是 bot 自己
     */
    public static boolean banAffectsBot(JsonNode event) {
        return sameAsSelfId(event, "user_id");
    }

    /**
     * group_decrease 的归属：sub_type=kick_me → 受影响者是 bot 自己
     */
    public static boolean decreaseAffectsBot(JsonNode event) {
        return "kick_me".equals(event.path("sub_type").asText());
    }

    private static boolean sameAsSelfId(JsonNode event, String field) {
        long selfId = event.path("self_id").asLong(Long.MIN_VALUE);
        return selfId != Long.MIN_VALUE && event.path(field).asLong(Long.MIN_VALUE) == selfId;
    }
}
