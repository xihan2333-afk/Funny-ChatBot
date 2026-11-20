package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.GroupEventPayload;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 群维度事件翻译器（data-model.md 第 4 节映射表 GroupEvent 行）：OneBot 11 对群信息变更
 * 无标准事件，主要承接 NapCat 扩展 group_name（新群名透传 extension）。
 */
@Component
public class GroupEventCodec {

    /**
     * @return 事件属于本原语时翻译的信封；不认识返回 null（由兜底语义接手）
     */
    public EventEnvelope toEnvelope(JsonNode event, String rawFrame,
                                    cn.edu.scut.chatbot.contract.envelope.RawEventRef rawRef,
                                    long receivedAtMillis, String eventId, String dedupKey) {
        if (!"notice".equals(event.path("post_type").asText())
                || !"group_name".equals(event.path("notice_type").asText())) {
            return null;
        }
        Map<String, String> extension = new HashMap<>();
        JsonNode groupName = event.get("group_name");
        if (groupName != null && !groupName.isNull()) {
            extension.put("ext.qq.group_name", groupName.asText());
        }
        GroupEventPayload payload = new GroupEventPayload(GroupEventPayload.SUB_INFO_CHANGED);
        return new EventEnvelope(
                eventId, null, dedupKey, PlatformCodes.ONEBOT_V11,
                textOrNull(event, "self_id"), textOrNull(event, "group_id"),
                textOrNull(event, "user_id"), PrimitiveTypeCodes.GROUP_EVENT,
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
