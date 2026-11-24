package cn.edu.scut.chatbot.engine.session;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.AudioPart;
import cn.edu.scut.chatbot.contract.message.FacePart;
import cn.edu.scut.chatbot.contract.message.ImagePart;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.QuotePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.message.UnsupportedPart;
import cn.edu.scut.chatbot.contract.message.VideoPart;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AgentMessageKind;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.DeliveryReportEntry;
import cn.edu.scut.chatbot.loop.message.EventEntry;
import cn.edu.scut.chatbot.loop.message.ImageContent;
import cn.edu.scut.chatbot.loop.message.LinkCardEntry;
import cn.edu.scut.chatbot.loop.message.SummaryEntry;
import cn.edu.scut.chatbot.loop.message.SystemNote;
import cn.edu.scut.chatbot.loop.message.TextContent;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 转录条目编解码器：AgentMessage ↔ payload JSON（平铺多态——payload 顶层带 kind 判别字段，
 * 各自 record 字段平铺，与信封线格式同款纪律）。occurredAt 统一 epoch 毫秒。
 *
 * <p>消息段（MessagePart）序列化走 record 直出（具体段字段平铺）；反序列化按段内
 * {@code part} 字段查段映射表——与接入层 EnvelopeCodec 的段多态同构。
 */
@Component
public class TranscriptCodec {

    private final ObjectMapper mapper = JsonMappers.shared();

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
     * AgentMessage → payload JSON：record 直出平铺 + 顶层补 kind 判别字段
     */
    public String encode(AgentMessage message) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(message);
            node.put("kind", message.kind().code());
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("转录条目序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * payload JSON + kind → AgentMessage
     */
    public AgentMessage decode(String payloadJson, String kindCode) {
        try {
            JsonNode node = mapper.readTree(payloadJson);
            AgentMessageKind kind = AgentMessageKind.fromCode(kindCode);
            switch (kind) {
                case USER -> {
                    return new UserMessage(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 50),
                            text(node, "sourceRef"), text(node, "senderUserId"),
                            deserializeParts(node.get("parts")));
                }
                case ASSISTANT -> {
                    List<AssistantMessage.ToolCallSegment> toolCalls = new ArrayList<>();
                    if (node.get("toolCalls") != null && node.get("toolCalls").isArray()) {
                        for (JsonNode call : node.get("toolCalls")) {
                            toolCalls.add(new AssistantMessage.ToolCallSegment(
                                    call.path("callId").asText(), call.path("name").asText(),
                                    call.path("argumentsJson").asText()));
                        }
                    }
                    JsonNode usageNode = node.path("usage");
                    return new AssistantMessage(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 60),
                            text(node, "sourceRef"), text(node, "textContent"), toolCalls,
                            text(node, "stopReason"),
                            new AssistantMessage.TokenUsage(
                                    usageNode.path("inputTokens").asInt(0),
                                    usageNode.path("outputTokens").asInt(0),
                                    usageNode.path("reasoningTokens").asInt(0),
                                    usageNode.path("totalTokens").asInt(0)));
                }
                case TOOL_RESULT -> {
                    List<cn.edu.scut.chatbot.loop.message.ContentPart> contents = new ArrayList<>();
                    if (node.get("content") != null && node.get("content").isArray()) {
                        for (JsonNode content : node.get("content")) {
                            // 内容段判别按字段存在性（record 直出无判别字段：有 storedRef 即图片引用）
                            if (content.has("storedRef")) {
                                contents.add(new ImageContent(content.path("storedRef").asText()));
                            } else {
                                contents.add(new TextContent(content.path("text").asText()));
                            }
                        }
                    }
                    return new ToolResultMessage(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 45),
                            text(node, "sourceRef"), text(node, "callId"), text(node, "toolName"),
                            contents, node.path("isError").asBoolean(false),
                            node.path("terminate").asBoolean(false),
                            mapper.convertValue(node.path("attachments"),
                                    mapper.getTypeFactory().constructMapType(
                                            Map.class, String.class, Object.class)));
                }
                case EVENT -> {
                    return new EventEntry(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 20),
                            text(node, "sourceRef"), text(node, "eventCategory"),
                            mapper.convertValue(node.path("attributes"),
                                    mapper.getTypeFactory().constructMapType(
                                            Map.class, String.class, String.class)),
                            text(node, "summaryLine"));
                }
                case DELIVERY_REPORT -> {
                    return new DeliveryReportEntry(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 50),
                            text(node, "sourceRef"), text(node, "outboxId"), text(node, "status"),
                            text(node, "failureKind"), text(node, "failureDetail"));
                }
                case SYSTEM_NOTE -> {
                    return new SystemNote(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 50),
                            text(node, "sourceRef"), text(node, "injectionSource"),
                            text(node, "promptText"));
                }
                case LINK_CARD -> {
                    return new LinkCardEntry(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 35),
                            text(node, "sourceRef"), text(node, "normalizedUrl"),
                            text(node, "sharerUserId"), node.path("level").asInt(1),
                            text(node, "title"), text(node, "siteName"), text(node, "summary"));
                }
                case SUMMARY -> {
                    return new SummaryEntry(
                            text(node, "messageId"), instant(node), intOr(node, "weight", 50),
                            text(node, "sourceRef"), text(node, "summaryText"),
                            text(node, "coveredRange"));
                }
                default -> throw new IllegalStateException("未知转录 kind：" + kindCode);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("转录条目反序列化失败（kind=" + kindCode + "）：" + e.getMessage(), e);
        }
    }

    private List<MessagePart> deserializeParts(JsonNode partsNode) throws Exception {
        List<MessagePart> parts = new ArrayList<>();
        if (partsNode != null && partsNode.isArray()) {
            for (JsonNode partNode : partsNode) {
                String partType = partNode.path("part").asText(null);
                Class<? extends MessagePart> partClass = partType == null ? null : PART_TYPES.get(partType);
                if (partClass == null) {
                    throw new IllegalStateException("未知消息段 part 标记：" + partType);
                }
                parts.add(mapper.treeToValue(partNode, partClass));
            }
        }
        return parts;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Instant instant(JsonNode node) {
        JsonNode value = node.get("occurredAt");
        return value == null || value.isNull() ? null : Instant.ofEpochMilli(value.asLong());
    }

    private int intOr(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asInt(defaultValue);
    }
}
