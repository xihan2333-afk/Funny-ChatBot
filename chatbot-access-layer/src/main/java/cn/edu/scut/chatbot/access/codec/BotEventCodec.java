package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.BotEventPayload;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Bot 自身事件翻译器（data-model.md 第 4 节映射表 BotEvent 行）：被踢出群、
 * 被禁言 / 解禁（受影响者是 bot 自己的分支）、新好友、QQ 会话掉线。
 * bot_offline 是「WS 不断、事件停摆」的提前感知信号（research R2），Agent 群生命周期与运维用它。
 */
@Component
public class BotEventCodec {

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
            case "group_decrease" -> {
                // 仅 kick_me 归 BotEvent（leave / kick 是普通成员事件）
                if (!EventOwnership.decreaseAffectsBot(event)) {
                    return null;
                }
                subType = BotEventPayload.SUB_BOT_KICKED;
            }
            case "group_ban" -> {
                // 仅受影响者是 bot 自己的禁言归 BotEvent
                if (!EventOwnership.banAffectsBot(event)) {
                    return null;
                }
                subType = "ban".equals(noticeSubType) ? BotEventPayload.SUB_BOT_MUTED
                        : "lift_ban".equals(noticeSubType) ? BotEventPayload.SUB_BOT_UNMUTED : null;
                if ("ban".equals(noticeSubType)) {
                    durationSeconds = event.path("duration").asLong();
                }
                extension.put("ext.qq.ban_sub_type", noticeSubType);
            }
            case "friend_add" -> subType = BotEventPayload.SUB_FRIEND_ADDED;
            // NapCat 扩展：QQ 会话掉线（WS 连接通常还活着，事件流停摆的提前感知）
            case "bot_offline" -> subType = BotEventPayload.SUB_BOT_OFFLINE;
            default -> {
                return null;
            }
        }
        if (subType == null) {
            return null;
        }

        BotEventPayload payload = new BotEventPayload(
                subType, textOrNull(event, "operator_id"), durationSeconds,
                textOrNull(event, "group_id"));
        // 信封层 groupId 与 payload 同值双写：Agent 路由按信封三元组（宪法第四条），
        // 群内 Bot 事件（bot_kicked / bot_muted）不能丢群归属；全局事件（friend_added）两边都是 null
        return new EventEnvelope(
                eventId, null, dedupKey, PlatformCodes.ONEBOT_V11,
                textOrNull(event, "self_id"), payload.groupId(),
                textOrNull(event, "user_id"), PrimitiveTypeCodes.BOT_EVENT,
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
