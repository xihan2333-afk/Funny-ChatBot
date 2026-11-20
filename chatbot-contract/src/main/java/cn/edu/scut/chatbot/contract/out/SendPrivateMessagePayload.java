package cn.edu.scut.chatbot.contract.out;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.message.MessagePart;

import java.util.List;

/**
 * 发私聊消息载荷（data-model.md 第 7 节）。<b>第一版预留</b>：Codec 拒绝并回执 unsupported。
 *
 * @param parts            多模态消息段列表
 * @param replyToMessageId 要引用回复的平台消息 ID，可空
 */
public record SendPrivateMessagePayload(
        List<MessagePart> parts,
        String replyToMessageId
) implements EnvelopePayload {
}
