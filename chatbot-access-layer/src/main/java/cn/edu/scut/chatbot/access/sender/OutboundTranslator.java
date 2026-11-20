package cn.edu.scut.chatbot.access.sender;

import cn.edu.scut.chatbot.access.infrastructure.ChatBotException;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.AudioPart;
import cn.edu.scut.chatbot.contract.message.FacePart;
import cn.edu.scut.chatbot.contract.message.ImagePart;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.QuotePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.message.VideoPart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 出方向发送翻译（data-model.md 第 7 节 + 契约 onebot-ws-adapter.md 第 3 节）：
 * 出原语 → OneBot API 调用参数（action + params JSON）。
 *
 * <p>平台无关契约在接入层内翻译为平台方言：SetReaction 的 isCancel 与平台参数 set 取反；
 * 出方向媒体装载（读取 storedRef 本地文件 → base64:// 内联进消息段）一步完成——
 * OneBot 11 无独立富媒体上传 API，无先上传后引用的第二跳。
 * 本地文件缺失抛 adapter.media.load_failed（由投递任务回执 media_missing，不无限重试）。
 */
@Component
public class OutboundTranslator {

    /**
     * 一次 API 调用的翻译产物：action 名与 params 的标准 JSON 字符串
     */
    public record ApiCall(String action, String paramsJson) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final long maxInlineMediaBytes;
    /**
     * 出方向媒体只允许从转存卷内读取——出事件载荷是半信任输入，storedRef 不得指向卷外任意文件
     */
    private final Path mediaStoreRoot;

    public OutboundTranslator(@Value("${access.outbound.max-inline-media-bytes:4194304}") long maxInlineMediaBytes,
                              @Value("${access.media.store-root:./data/media}") String mediaStoreRoot) {
        this.maxInlineMediaBytes = maxInlineMediaBytes;
        this.mediaStoreRoot = Path.of(mediaStoreRoot).toAbsolutePath().normalize();
    }

    /**
     * send_group_message → send_group_msg（段数组组装，replyToMessageId 转 reply 段打头）
     */
    public ApiCall translateSendGroupMessage(String groupId, List<MessagePart> parts, String replyToMessageId) {
        ObjectNode params = json.createObjectNode();
        params.put("group_id", groupId);
        params.put("auto_escape", false);
        ArrayNode message = params.putArray("message");
        if (replyToMessageId != null && !replyToMessageId.isBlank()) {
            ObjectNode replySegment = message.addObject();
            replySegment.put("type", "reply");
            replySegment.putObject("data").put("id", replyToMessageId);
        }
        for (MessagePart part : parts) {
            appendOutboundSegment(message, part);
        }
        return new ApiCall("send_group_msg", params.toString());
    }

    /**
     * recall_message → delete_msg
     */
    public ApiCall translateRecallMessage(String platformMessageId) {
        ObjectNode params = json.createObjectNode();
        params.put("message_id", platformMessageId);
        return new ApiCall("delete_msg", params.toString());
    }

    /**
     * set_reaction → set_msg_emoji_like（NapCat 现行扩展 API；isCancel 与平台 set 取反翻译）
     */
    public ApiCall translateSetReaction(String platformMessageId, String emojiId, boolean isCancel) {
        ObjectNode params = json.createObjectNode();
        params.put("message_id", platformMessageId);
        params.put("emoji_id", emojiId);
        params.put("set", !isCancel);
        return new ApiCall("set_msg_emoji_like", params.toString());
    }

    /**
     * send_interaction_reply → set_friend_add_request / set_group_add_request。
     * 判定规则：加群请求必然关联群——信封 groupId 非空走加群 API，为空走好友 API
     * （判据用信封标准字段，不新增契约字段）。加群 API 的 sub_type（add / invite）出原语
     * 不携带，默认按 add 处理（绝大多数加群请求形态；invite 场景为第二版演进项）。
     */
    public ApiCall translateInteractionReply(String groupId, String requestFlag, boolean approve, String reason) {
        ObjectNode params = json.createObjectNode();
        params.put("flag", requestFlag);
        params.put("approve", approve);
        if (reason != null && !reason.isBlank()) {
            params.put("reason", reason);
        }
        boolean isGroupRequest = groupId != null && !groupId.isBlank();
        if (isGroupRequest) {
            params.put("sub_type", "add");
        }
        return new ApiCall(isGroupRequest ? "set_group_add_request" : "set_friend_add_request",
                params.toString());
    }

    /**
     * 消息段出方向翻译：媒体段装载为 base64:// 内联，其余段直译
     */
    private void appendOutboundSegment(ArrayNode message, MessagePart part) {
        if (part instanceof TextPart text) {
            segment(message, "text").put("text", text.text());
        } else if (part instanceof AtPart at) {
            segment(message, "at").put("qq", at.targetUserId());
        } else if (part instanceof QuotePart quote) {
            segment(message, "reply").put("id", quote.referencedMessageId());
        } else if (part instanceof FacePart face) {
            segment(message, "face").put("id", face.faceId());
        } else if (part instanceof ImagePart image) {
            segment(message, "image").put("file", inlineMediaContent(image.storedRef(), image.fileName()));
        } else if (part instanceof AudioPart audio) {
            segment(message, "record").put("file", inlineMediaContent(audio.storedRef(), audio.fileName()));
        } else if (part instanceof VideoPart video) {
            segment(message, "video").put("file", inlineMediaContent(video.storedRef(), video.fileName()));
        } else {
            // UnsupportedPart 等未建模段：出方向无平台方言可翻，跳过（Agent 不应构造，防御性容错）
        }
    }

    private ObjectNode segment(ArrayNode message, String type) {
        ObjectNode segmentNode = message.addObject();
        segmentNode.put("type", type);
        return segmentNode.putObject("data");
    }

    /**
     * 读取本地媒体文件并 base64 内联；缺失、超限或<b>逃逸出媒体卷</b>均抛 adapter.media.load_failed。
     * 路径约束：出事件载荷来自 Redis 流（半信任输入），storedRef 必须解析到转存卷内部——
     * 被带偏的 Agent 发 "/app/.env" 之类路径时在这里挡下（宪法第六条「被骗了也干不成坏事」）。
     */
    private String inlineMediaContent(String storedRef, String fileName) {
        if (storedRef == null || storedRef.isBlank()) {
            throw new ChatBotException(ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED,
                    "出方向媒体段缺少 storedRef：" + fileName);
        }
        Path file = Path.of(storedRef).toAbsolutePath().normalize();
        if (!file.startsWith(mediaStoreRoot)) {
            throw new ChatBotException(ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED,
                    "出方向媒体路径逃逸出转存卷（storedRef=" + storedRef
                            + "，允许根=" + mediaStoreRoot + "），已拒绝读取");
        }
        if (!Files.isRegularFile(file)) {
            throw new ChatBotException(ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED,
                    "出方向媒体文件不存在（storedRef=" + storedRef + "），按 media_missing 回执不无限重试");
        }
        try {
            long size = Files.size(file);
            if (size > maxInlineMediaBytes) {
                throw new ChatBotException(ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED,
                        "出方向媒体超过内联上限 " + maxInlineMediaBytes + " 字节（" + storedRef + "）");
            }
            return "base64://" + Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new ChatBotException(ErrorCodes.ADAPTER_MEDIA_LOAD_FAILED,
                    "出方向媒体文件读取失败（" + storedRef + "）：" + e.getMessage(), e);
        }
    }
}
