package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException;
import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.envelope.RawEventRef;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 事件信封 JSON 编解码器（契约 contracts/event-envelope.md 第 1 节）。
 *
 * <p>平铺多态双向编解码：序列化直接走 record（字段名即 JSON 键）；反序列化先读信封顶层
 * {@code type}，查映射表把 payload 平铺对象还原为对应 record——不使用 Jackson 默认多态注解，
 * 载荷不出现 Java 类名与 @class 类型信息，两端类名解耦。
 *
 * <p>消息段（MessagePart）同理按段内 {@code part} 字段查段映射表还原。
 *
 * <p>显式持有独立 ObjectMapper 单例：不共享 Spring 自动配置的全局实例，
 * 编解码行为完全由本类控制，避免别处改动序列化配置悄悄改变线格式。
 */
@Component
public class EnvelopeCodec {

    private final ObjectMapper mapper = new ObjectMapper();

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
     * 信封 → JSON 字符串（写入 Redis Stream 的 envelope 字段值）
     */
    public String serialize(EventEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // record 全是简单类型，序列化失败只可能是代码 bug（如 extension 塞了非字符串键值）
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
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
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                    "信封不是合法 JSON：" + e.getMessage(), e);
        }
        if (root == null || !root.isObject() || root.path("type").isMissingNode()) {
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                    "信封缺少顶层 type 字段，无法按平铺多态解释 payload");
        }
        String type = root.get("type").asText();
        Class<? extends EnvelopePayload> payloadClass = PAYLOAD_TYPES.get(type);
        if (payloadClass == null) {
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                    "未知信封 type：" + type + "（本版本接入层不认识该原语，按毒消息处理）");
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
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                    "信封反序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 还原 payload record。含消息段列表的四种消息原语手动还原段多态，
     * 其余类型无接口类型字段、直接由 Jackson 按 record 组件绑定。
     */
    private EnvelopePayload deserializePayload(String type, JsonNode node, Class<? extends EnvelopePayload> payloadClass) throws JsonProcessingException {
        if (node == null || node.isNull()) {
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
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

    /**
     * 段列表平铺多态还原：按每个段对象的 part 字段查段映射表
     */
    private List<MessagePart> deserializeParts(JsonNode partsNode) {
        if (partsNode == null || !partsNode.isArray()) {
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                    "消息段 parts 必须是非空数组");
        }
        List<MessagePart> parts = new ArrayList<>(partsNode.size());
        for (JsonNode partNode : partsNode) {
            String partType = partNode.path("part").asText(null);
            Class<? extends MessagePart> partClass = partType == null ? null : PART_TYPES.get(partType);
            if (partClass == null) {
                throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                        "未知消息段 part 标记：" + partType);
            }
            try {
                parts.add(mapper.treeToValue(partNode, partClass));
            } catch (JsonProcessingException e) {
                throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
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
