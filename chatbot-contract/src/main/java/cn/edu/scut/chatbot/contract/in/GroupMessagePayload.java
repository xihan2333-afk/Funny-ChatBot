package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.message.MessagePart;

import java.util.List;

/**
 * 群消息载荷（data-model.md 第 4 节）。OneBot 来源：message.group 事件。
 *
 * <p>验证规则：groupId 在信封层必填；parts 非空；messageId 非空（它是 dedupKey 来源）。
 *
 * @param messageId 平台 message_id 字符串（number/string 双形态已由 Codec 容忍转换）
 * @param subType   normal / anonymous / notice
 * @param sender    发送者资料
 * @param parts     多模态消息段列表
 * @param rawText   CQ 码原文，排障用（信息保真，不参与业务）
 */
public record GroupMessagePayload(
        String messageId,
        String subType,
        SenderProfile sender,
        List<MessagePart> parts,
        String rawText
) implements EnvelopePayload {
}
