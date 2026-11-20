package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.MemberEventPayload;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 群成员事件翻译器（data-model.md 第 4 节映射表 MemberEvent 行）：入群、退群、被踢、
 * 被禁言 / 解禁（受影响者为普通成员的分支）、管理员任免、群名片变更。
 * 归属判定在 {@link EventOwnership}（受影响者是 bot 自己的事件归 BotEventCodec）。
 */
@Component
public class MemberEventCodec {

    /**
     * @return 事件属于本原语时翻译的信封；不认识返回 null（由兜底语义接手）
     */
    public EventEnvelope toEnvelope(JsonNode event, String rawFrame,
                                    cn.edu.scut.chatbot.contract.envelope.RawEventRef rawRef,
                                    long receivedAtMillis, String eventId, String dedupKey) {
        if (!"notice".equals(event.path("post_type").asText())) {
            return null;
        }
        String noticeType = event.path("notice_type").asText();
        String noticeSubType = event.path("sub_type").asText();

        String subType;
        Long durationSeconds = null;
        Map<String, String> extension = new HashMap<>();

        switch (noticeType) {
            case "group_increase" -> subType = MemberEventPayload.SUB_MEMBER_JOIN;
            case "group_decrease" -> {
                // leave 主动退群 / kick 被踢（kick_me 归 BotEvent，不在此列）
                subType = "leave".equals(noticeSubType) ? MemberEventPayload.SUB_MEMBER_LEAVE
                        : "kick".equals(noticeSubType) ? MemberEventPayload.SUB_MEMBER_KICKED
                        : null;
            }
            case "group_admin" -> subType = "set".equals(noticeSubType)
                    ? MemberEventPayload.SUB_ADMIN_GRANTED
                    : "unset".equals(noticeSubType) ? MemberEventPayload.SUB_ADMIN_REVOKED : null;
            case "group_ban" -> {
                // 受影响者是普通成员的禁言分支（user_id == self_id 归 BotEvent）
                if (EventOwnership.banAffectsBot(event)) {
                    return null;
                }
                subType = "ban".equals(noticeSubType) ? MemberEventPayload.SUB_MUTED
                        : "lift_ban".equals(noticeSubType) ? MemberEventPayload.SUB_UNMUTED : null;
                if ("ban".equals(noticeSubType)) {
                    durationSeconds = event.path("duration").asLong();
                }
                extension.put("ext.qq.ban_sub_type", noticeSubType);
            }
            case "group_card" -> subType = MemberEventPayload.SUB_CARD_CHANGED;
            default -> {
                return null;
            }
        }
        if (subType == null) {
            return null;
        }

        MemberEventPayload payload = new MemberEventPayload(
                subType, textOrNull(event, "operator_id"), durationSeconds);
        return new EventEnvelope(
                eventId, null, dedupKey, PlatformCodes.ONEBOT_V11,
                textOrNull(event, "self_id"), textOrNull(event, "group_id"),
                textOrNull(event, "user_id"), PrimitiveTypeCodes.MEMBER_EVENT,
                payload, occurredAtOrNull(event), receivedAtMillis,
                extension, rawRef, null);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * time 字段缺失 / 为 null 时 occurredAt 保持 null（不能用 0 假时间冒充 1970 年）
     */
    private Long occurredAtOrNull(JsonNode event) {
        return event.hasNonNull("time") ? event.path("time").asLong() * 1000 : null;
    }
}
