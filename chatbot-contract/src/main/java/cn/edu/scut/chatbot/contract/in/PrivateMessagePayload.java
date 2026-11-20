package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.message.MessagePart;

import java.util.List;

/**
 * 私聊消息载荷。OneBot 来源：message.private 事件。
 *
 * <p><b>第一版预留不实现</b>：Codec 遇到 message_type=private 收敛为 RawEvent
 * （保真不丢失，Agent 不消费），本 record 只占位契约形态。
 *
 * @param messageId 平台 message_id 字符串
 * @param subType   friend / group / other
 * @param sender    发送者资料
 * @param parts     多模态消息段列表
 * @param rawText   CQ 码原文，排障用
 */
public record PrivateMessagePayload(
        String messageId,
        String subType,
        SenderProfile sender,
        List<MessagePart> parts,
        String rawText
) implements EnvelopePayload {
}
