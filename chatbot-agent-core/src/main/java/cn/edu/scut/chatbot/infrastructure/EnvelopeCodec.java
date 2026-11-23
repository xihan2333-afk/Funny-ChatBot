package cn.edu.scut.chatbot.infrastructure;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.envelope.RawEventRef;
import cn.edu.scut.chatbot.contract.in.BotEventPayload;
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload;
import cn.edu.scut.chatbot.contract.in.GroupEventPayload;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.in.InteractionPayload;
import cn.edu.scut.chatbot.contract.in.MemberEventPayload;
import cn.edu.scut.chatbot.contract.in.PrivateMessagePayload;
import cn.edu.scut.chatbot.contract.in.RawEventPayload;
import cn.edu.scut.chatbot.contract.in.SenderProfile;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.AudioPart;
import cn.edu.scut.chatbot.contract.message.FacePart;
import cn.edu.scut.chatbot.contract.message.ImagePart;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.QuotePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.message.UnsupportedPart;
import cn.edu.scut.chatbot.contract.message.VideoPart;
import cn.edu.scut.chatbot.contract.out.RecallMessagePayload;
import cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload;
import cn.edu.scut.chatbot.contract.out.SendInteractionReplyPayload;
import cn.edu.scut.chatbot.contract.out.SendPrivateMessagePayload;
import cn.edu.scut.chatbot.contract.out.SetReactionPayload;
import cn.edu.scut.chatbot.engine.EngineErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 事件信封 JSON 编解码器（agent-core 侧）：与接入层 EnvelopeCodec 同构的平铺多态双向编解码，
 * 两端各自实现、不共享类——Redis 双流是唯一共享面，线格式（字段平铺、type 查映射表）一致即可。
 *
 * <p>主服务既消费入流（反序列化）也投递出流（序列化），本组件是双流 JSON 的唯一翻译点；
 * ObjectMapper 走全局共享单例（{@link JsonMappers}）。
 */
@Component
public class EnvelopeCodec {

    private final ObjectMapper mapper = JsonMappers.shared();

    /**
     * 信封 type 短码 → payload record 类（平铺多态反序列化的映射表）
     */
    private static final Map<String, Class<? extends EnvelopePayload>> PAYLOAD_TYPES = Map.ofEntries(
            Map.entry(PrimitiveTypeCodes.GROUP_MESSAGE, GroupMessagePayload.class),
            Map.entry(PrimitiveTypeCodes.PRIVATE_MESSAGE, PrivateMessagePayload.class),
            Map.entry(PrimitiveTypeCodes.INTERACTION, InteractionPayload.class),
            Map.entry(PrimitiveTypeCodes.MEMBER_EVENT, MemberEventPayload.class),
            Map.entry(PrimitiveTypeCodes.GROUP_EVENT, GroupEventPayload.class),
            Map.entry(PrimitiveTypeCodes.BOT_EVENT, BotEventPayload.class),
            Map.entry(PrimitiveTypeCodes.DELIVERY_REPORT, DeliveryReportPayload.class),
            Map.entry(PrimitiveTypeCodes.RAW_EVENT, RawEventPayload.class),
            Map.entry(PrimitiveTypeCodes.SEND_GROUP_MESSAGE, SendGroupMessagePayload.class),
            Map.entry(PrimitiveTypeCodes.SEND_PRIVATE_MESSAGE, SendPrivateMessagePayload.class),
            Map.entry(PrimitiveTypeCodes.RECALL_MESSAGE, RecallMessagePayload.class),
            Map.entry(PrimitiveTypeCodes.SET_REACTION, SetReactionPayload.class),
            Map.entry(PrimitiveTypeCodes.SEND_INTERACTION_REPLY, SendInteractionReplyPayload.class));

    /**
     * 消息段 part 短码 → 段 record 类
     */
    private static final Map<String, Class<? extends MessagePart>> PART_TYPES = Map.of(
            TextPart.TYPE_TEXT, TextPart.class,
            AtPart.TYPE_AT, AtPart.class,
            QuotePart.TYPE_QUOTE, QuotePart.class,
            ImagePart.TYPE_IMAGE, ImagePart.class,
            AudioPart.TYPE_AUDIO, AudioPart.class,
            VideoPart.TYPE_VIDEO, VideoPart.class,
            FacePart.TYPE_FACE, FacePart.class,
            UnsupportedPart.TYPE_UNSUPPORTED, UnsupportedPart.class);

    /**
     * 信封 → JSON 字符串（XADD 出流的 envelope 字段值）
     */
    public String serialize(EventEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new NonRetryableException(EngineErrorCodes.OUTBOX_DISPATCH_FAILED,
                    "信封序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * JSON 字符串 → 信封；结构坏 / type 未知抛 NonRetryableException（调用方按毒消息处理）
     */
    public EventEnvelope deserialize(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                    "信封不是合法 JSON：" + e.getMessage(), e);
        }
        if (root == null || !root.isObject() || root.path("type").isMissingNode()) {
            throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                    "信封缺少顶层 type 字段，无法按平铺多态解释 payload");
        }
        String type = root.get("type").asText();
        Class<? extends EnvelopePayload> payloadClass = PAYLOAD_TYPES.get(type);
        if (payloadClass == null) {
            throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                    "未知信封 type：" + type + "（按毒消息处理）");
        }
        try {
            EnvelopePayload payload = deserializePayload(type, root.get("payload"), payloadClass);
            return new EventEnvelope(
                    textOrNull(root, "eventId"),
                    textOrNull(root, "outboxId"),
                    textOrNull(root, "dedupKey"),
                    textOrNull(root, "platform"),
                    textOrNull(root, "botId"),
                    textOrNull(root, "groupId"),
                    textOrNull(root, "userId"),
                    type,
                    payload,
                    longOrNull(root, "occurredAt"),
                    longOrNull(root, "receivedAt"),
                    stringMap(root.get("extension")),
                    rawRefOrNull(root.get("rawRef")),
                    textOrNull(root, "traceContext"));
        } catch (NonRetryableException e) {
            throw e;
        } catch (Exception e) {
            throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                    "信封反序列化失败：" + e.getMessage(), e);
        }
    }

    private EnvelopePayload deserializePayload(String type, JsonNode node,
                                               Class<? extends EnvelopePayload> payloadClass) throws JsonProcessingException {
        if (node == null || node.isNull()) {
            throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                    "type=" + type + " 的信封 payload 为空");
        }
        switch (type) {
            case PrimitiveTypeCodes.GROUP_MESSAGE:
                return new GroupMessagePayload(
                        textOrNull(node, "messageId"),
                        textOrNull(node, "subType"),
                        mapper.treeToValue(node.get("sender"), SenderProfile.class),
                        deserializeParts(node.get("parts")),
                        textOrNull(node, "rawText"));
            case PrimitiveTypeCodes.PRIVATE_MESSAGE:
                return new PrivateMessagePayload(
                        textOrNull(node, "messageId"),
                        textOrNull(node, "subType"),
                        mapper.treeToValue(node.get("sender"), SenderProfile.class),
                        deserializeParts(node.get("parts")),
                        textOrNull(node, "rawText"));
            case PrimitiveTypeCodes.SEND_GROUP_MESSAGE:
                return new SendGroupMessagePayload(
                        deserializeParts(node.get("parts")),
                        textOrNull(node, "replyToMessageId"));
            case PrimitiveTypeCodes.SEND_PRIVATE_MESSAGE:
                return new SendPrivateMessagePayload(
                        deserializeParts(node.get("parts")),
                        textOrNull(node, "replyToMessageId"));
            default:
                return mapper.treeToValue(node, payloadClass);
        }
    }

    private List<MessagePart> deserializeParts(JsonNode partsNode) {
        if (partsNode == null || !partsNode.isArray()) {
            throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                    "消息段 parts 必须是非空数组");
        }
        List<MessagePart> parts = new ArrayList<>(partsNode.size());
        for (JsonNode partNode : partsNode) {
            String partType = partNode.path("part").asText(null);
            Class<? extends MessagePart> partClass = partType == null ? null : PART_TYPES.get(partType);
            if (partClass == null) {
                throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                        "未知消息段 part 标记：" + partType);
            }
            try {
                parts.add(mapper.treeToValue(partNode, partClass));
            } catch (JsonProcessingException e) {
                throw new NonRetryableException(EngineErrorCodes.INTAKE_POISON_ENVELOPE,
                        "消息段还原失败（part=" + partType + "）：" + e.getMessage(), e);
            }
        }
        return parts;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new HashMap<>();
        if (node != null && node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.put(field.getKey(), field.getValue().asText());
            }
        }
        return result;
    }

    private RawEventRef rawRefOrNull(JsonNode node) throws JsonProcessingException {
        return node == null || node.isNull() ? null : mapper.treeToValue(node, RawEventRef.class);
    }
}
