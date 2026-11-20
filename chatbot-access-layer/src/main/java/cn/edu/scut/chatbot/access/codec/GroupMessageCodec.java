package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.in.SenderProfile;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.AudioPart;
import cn.edu.scut.chatbot.contract.message.FacePart;
import cn.edu.scut.chatbot.contract.message.ImagePart;
import cn.edu.scut.chatbot.contract.message.MediaTransferStatusCodes;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.QuotePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.message.UnsupportedPart;
import cn.edu.scut.chatbot.contract.message.VideoPart;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 群消息翻译器（data-model.md 第 4 节映射表）：OneBot message.group 事件 → group_message 信封。
 *
 * <p>标准字段全量翻译；平台特有语义只进 extension（匿名结构 ext.qq.anonymous、
 * 自发消息标记 ext.qq.self_sent）；occurredAt 由秒级 time 换算毫秒；dedupKey 取平台 message_id；
 * message_id / group_id / user_id 按 long 或 string 双形态容忍。
 * 未建模段（json / xml / share / forward / mface / file 等）保真为 UnsupportedPart。
 *
 * <p>媒体段初始状态：有 URL → pending（等转存尝试，由 MediaTransferService 替换为
 * transferred / failed）；无 URL → failed（无源可转存）。eventId 由管线统一发号后传入。
 */
@Component
public class GroupMessageCodec {

    private final OneBotMessageParser messageParser;

    public GroupMessageCodec(OneBotMessageParser messageParser) {
        this.messageParser = messageParser;
    }

    /**
     * @param event            已解析的 OneBot message.group / message_sent.group 事件
     * @param receivedAtMillis 接入层接收时刻（注入的 Clock 打点）
     * @param eventId          管线已发放的雪花 ID
     */
    public EventEnvelope toEnvelope(JsonNode event, long receivedAtMillis, String eventId) {
        Map<String, String> extension = new HashMap<>();

        JsonNode anonymous = event.path("anonymous");
        if (anonymous.isObject()) {
            extension.put("ext.qq.anonymous", anonymous.toString());
        }
        if ("message_sent".equals(event.path("post_type").asText())) {
            extension.put("ext.qq.self_sent", "true");
        }

        SenderProfile sender = toSenderProfile(event.path("sender"));
        List<MessagePart> parts = toMessageParts(messageParser.parseMessageField(event.get("message")));

        GroupMessagePayload payload = new GroupMessagePayload(
                textOrNull(event, "message_id"),
                textOrNull(event, "sub_type"),
                sender,
                parts,
                textOrNull(event, "raw_message"));

        return new EventEnvelope(
                eventId,
                null,
                textOrNull(event, "message_id"),
                PlatformCodes.ONEBOT_V11,
                textOrNull(event, "self_id"),
                textOrNull(event, "group_id"),
                textOrNull(event, "user_id"),
                PrimitiveTypeCodes.GROUP_MESSAGE,
                payload,
                occurredAtOrNull(event),
                receivedAtMillis,
                extension,
                null,
                null);
    }

    private SenderProfile toSenderProfile(JsonNode senderNode) {
        return new SenderProfile(
                textOrNull(senderNode, "user_id"),
                emptyIfNull(textOrNull(senderNode, "nickname")),
                emptyIfNull(textOrNull(senderNode, "card")),
                emptyIfNull(textOrNull(senderNode, "role")),
                emptyIfNull(textOrNull(senderNode, "level")),
                emptyIfNull(textOrNull(senderNode, "title")));
    }

    /**
     * 段类型分发：建模段映射标准语义，未建模段原样保真
     */
    private List<MessagePart> toMessageParts(List<OneBotMessageParser.ParsedSegment> segments) {
        List<MessagePart> parts = new ArrayList<>(segments.size());
        for (OneBotMessageParser.ParsedSegment segment : segments) {
            parts.add(toMessagePart(segment));
        }
        return parts;
    }

    private MessagePart toMessagePart(OneBotMessageParser.ParsedSegment segment) {
        String type = segment.type();
        Map<String, String> data = segment.data();
        switch (type) {
            case "text":
                return new TextPart(TextPart.TYPE_TEXT, data.getOrDefault("text", ""));
            case "at":
                String target = data.getOrDefault("qq", "");
                return new AtPart(AtPart.TYPE_AT, AtPart.TARGET_ALL.equals(target) ? AtPart.TARGET_ALL : target);
            case "reply":
                return new QuotePart(QuotePart.TYPE_QUOTE, data.get("id"));
            case "face":
                return new FacePart(FacePart.TYPE_FACE, data.get("id"));
            case "image":
                return new ImagePart(ImagePart.TYPE_IMAGE, data.get("url"), null,
                        initialTransferStatus(data.get("url")), data.get("file"),
                        parseLongOrNull(data.get("file_size")));
            case "record":
                return new AudioPart(AudioPart.TYPE_AUDIO, data.get("url"), null,
                        initialTransferStatus(data.get("url")), data.get("file"),
                        parseLongOrNull(data.get("file_size")));
            case "video":
                return new VideoPart(VideoPart.TYPE_VIDEO, data.get("url"), null,
                        initialTransferStatus(data.get("url")), data.get("file"),
                        parseLongOrNull(data.get("file_size")));
            default:
                // 未建模段：原段类型名与参数全量保留（信息不丢失的段级落点）
                return new UnsupportedPart(UnsupportedPart.TYPE_UNSUPPORTED, type, data);
        }
    }

    /**
     * 媒体段初始转存状态：有 URL → pending 待转存尝试；无 URL → failed（无源可转存）
     */
    private String initialTransferStatus(String url) {
        return url == null || url.isBlank()
                ? MediaTransferStatusCodes.FAILED
                : MediaTransferStatusCodes.PENDING;
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * time 字段缺失 / 为 null 时 occurredAt 保持 null（不能用 0 假时间冒充 1970 年）
     */
    private Long occurredAtOrNull(JsonNode event) {
        return event.hasNonNull("time") ? event.path("time").asLong() * 1000 : null;
    }
}
