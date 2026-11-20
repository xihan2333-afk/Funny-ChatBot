package cn.edu.scut.chatbot.access.codec;

import cn.edu.scut.chatbot.contract.message.*;

import java.util.List;

/**
 * 消息内容的日志摘要（观测专用）：把消息压成一行人可读文本并截断到固定长度。
 *
 * <p>只服务日志可观测性，不参与任何业务逻辑——排查「消息到了哪一步、是什么内容」时
 * 不必再回留档表查原文。文本段原样输出，@ 段转 {@code @QQ号}，媒体段转
 * {@code [图片:文件名]} 形态；空白压平、超长截断，保证一条消息永远只占一行日志。
 */
public final class MessageTextSummary {

    /**
     * 摘要长度上限（字符）：防长消息 / 恶意刷屏消息把日志撑爆
     */
    private static final int MAX_LENGTH = 200;

    private MessageTextSummary() {
    }

    /**
     * 消息段列表 → 单行摘要（出方向发送内容、入方向无原文时的结构化视图）
     */
    public static String ofParts(List<MessagePart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (MessagePart part : parts) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            if (part instanceof TextPart text) {
                builder.append(text.text());
            } else if (part instanceof AtPart at) {
                builder.append("@").append(at.targetUserId());
            } else if (part instanceof QuotePart quote) {
                builder.append("[回复:").append(quote.referencedMessageId()).append("]");
            } else if (part instanceof FacePart face) {
                builder.append("[表情:").append(face.faceId()).append("]");
            } else if (part instanceof ImagePart image) {
                builder.append("[图片").append(namedSuffix(image.fileName())).append("]");
            } else if (part instanceof AudioPart audio) {
                builder.append("[语音").append(namedSuffix(audio.fileName())).append("]");
            } else if (part instanceof VideoPart video) {
                builder.append("[视频").append(namedSuffix(video.fileName())).append("]");
            } else {
                // 兜底段（UnsupportedPart 等）：标记形态即可，观测上知道「有个未建模段」就够
                builder.append("[未建模段]");
            }
        }
        return compress(builder.toString());
    }

    /**
     * 原始文本 → 单行摘要（入方向消息的 rawText）
     */
    public static String ofRawText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "-";
        }
        return compress(rawText);
    }

    /**
     * 文件名后缀形态：":文件名"；无名媒体省略冒号直接闭括号
     */
    private static String namedSuffix(String fileName) {
        return fileName == null || fileName.isBlank() ? "" : ":" + fileName;
    }

    /**
     * 空白压平成单行并截断（截断以省略号标记结尾）
     */
    private static String compress(String text) {
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= MAX_LENGTH ? flattened : flattened.substring(0, MAX_LENGTH) + "…";
    }
}
