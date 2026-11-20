package cn.edu.scut.chatbot.contract.out;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * 设置表情回应载荷（data-model.md 第 7 节）。契约字段保持平台无关。
 *
 * <p>表情回应不是 OneBot 标准能力：Sender 走能力探测（NapCat 现行 API set_msg_emoji_like，
 * isCancel 与平台参数 set 取反的翻译在接入层内），协议端不支持时回执 unsupported 不再调用。
 *
 * @param platformMessageId 要回应的平台消息 ID
 * @param emojiId           表情编号（OneBot face 编号字符串）
 * @param isCancel          true 表示取消回应（平台参数 set=false）
 */
public record SetReactionPayload(
        String platformMessageId,
        String emojiId,
        boolean isCancel
) implements EnvelopePayload {
}
