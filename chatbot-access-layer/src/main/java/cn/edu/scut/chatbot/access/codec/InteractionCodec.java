package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.InteractionPayload;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 互动事件翻译器（data-model.md 第 4 节映射表 Interaction 行）：戳一戳、红包运、群荣誉、
 * 文件上传、消息撤回、好友 / 加群请求、表情回应、精华消息的收敛点。
 * 平台特有语义（poke_type / honor_type / request_flag 等）进 ext.qq.* 命名空间。
 */
@Component
public class InteractionCodec {

    /**
     * @return 事件属于本原语时翻译的信封；不认识返回 null（由兜底语义接手）
     */
    public EventEnvelope toEnvelope(JsonNode event, String rawFrame,
                                    cn.edu.scut.chatbot.contract.envelope.RawEventRef rawRef,
                                    long receivedAtMillis, String eventId, String dedupKey) {
        String postType = event.path("post_type").asText();
        Map<String, String> extension = new HashMap<>();

        String subType;
        String targetUserId = textOrNull(event, "target_id");
        String operatorUserId = textOrNull(event, "user_id");
        String requestFlag = null;

        if ("request".equals(postType)) {
            String requestType = event.path("request_type").asText();
            if ("friend".equals(requestType)) {
                subType = InteractionPayload.SUB_FRIEND_REQUEST;
            } else if ("group".equals(requestType)) {
                subType = InteractionPayload.SUB_GROUP_JOIN_REQUEST;
            } else {
                return null;
            }
            requestFlag = textOrNull(event, "flag");
            putIfPresent(extension, "ext.qq.request_flag", event, "flag");
        } else {
            String noticeType = event.path("notice_type").asText();
            String noticeSubType = event.path("sub_type").asText();
            switch (noticeType) {
                case "group_upload" -> subType = InteractionPayload.SUB_FILE_UPLOAD;
                case "group_recall", "friend_recall" -> subType = InteractionPayload.SUB_MESSAGE_RECALL;
                case "notify" -> {
                    subType = switch (noticeSubType) {
                        case "poke" -> InteractionPayload.SUB_POKE;
                        case "lucky_king" -> InteractionPayload.SUB_RED_PACKET_LUCKY;
                        case "honor" -> InteractionPayload.SUB_HONOR;
                        default -> null;
                    };
                    if (subType == null) {
                        return null;
                    }
                    if ("poke".equals(subType)) {
                        putIfPresent(extension, "ext.qq.poke_type", event, "poke_type");
                    }
                    if (InteractionPayload.SUB_HONOR.equals(subType)) {
                        putIfPresent(extension, "ext.qq.honor_type", event, "honor_type");
                    }
                }
                // NapCat 扩展事件
                case "msg_emoji_like" -> subType = InteractionPayload.SUB_REACTION;
                case "group_essence" -> subType = InteractionPayload.SUB_ESSENCE;
                default -> {
                    return null;
                }
            }
        }

        InteractionPayload payload = new InteractionPayload(
                subType, targetUserId, operatorUserId, requestFlag);
        return new EventEnvelope(
                eventId, null, dedupKey, PlatformCodes.ONEBOT_V11,
                textOrNull(event, "self_id"), textOrNull(event, "group_id"),
                textOrNull(event, "user_id"), PrimitiveTypeCodes.INTERACTION,
                payload, occurredAtOrNull(event), receivedAtMillis,
                extension, rawRef, null);
    }

    private void putIfPresent(Map<String, String> extension, String key, JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value != null && !value.isNull()) {
            extension.put(key, value.asText());
        }
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
