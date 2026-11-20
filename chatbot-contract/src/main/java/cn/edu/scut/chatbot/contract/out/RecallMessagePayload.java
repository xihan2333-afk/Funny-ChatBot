package cn.edu.scut.chatbot.contract.out;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * 撤回消息载荷（data-model.md 第 7 节）。Sender 翻译为 OneBot delete_msg。
 *
 * <p>注意：平台 message_id 是协议端本地短 ID，协议端进程重启后作废——
 * 跨重启引用会报 "msg not found"，按 failed 回执处理，属正常路径。
 *
 * @param platformMessageId 要撤回的平台消息 ID
 */
public record RecallMessagePayload(
        String platformMessageId
) implements EnvelopePayload {
}
