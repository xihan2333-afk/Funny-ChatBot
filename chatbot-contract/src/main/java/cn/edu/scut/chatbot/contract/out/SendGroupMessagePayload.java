package cn.edu.scut.chatbot.contract.out;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.message.MessagePart;

import java.util.List;

/**
 * 发群消息载荷（data-model.md 第 7 节）。Agent → 接入层，Sender 翻译为 OneBot send_group_msg。
 *
 * <p>出方向媒体：Agent 给 storedRef（接入层可解析的本地路径），originalUrl 为空；
 * Sender 读取文件以 base64:// 内联进消息段（OneBot 11 无独立上传 API，无第二跳）。
 *
 * @param parts            多模态消息段列表
 * @param replyToMessageId 要引用回复的平台消息 ID，可空（等价 QuotePart 的便捷入口）
 */
public record SendGroupMessagePayload(
        List<MessagePart> parts,
        String replyToMessageId
) implements EnvelopePayload {
}
