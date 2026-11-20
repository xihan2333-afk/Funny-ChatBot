package cn.edu.scut.chatbot.contract.out;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * 处理互动请求载荷（data-model.md 第 7 节）：对好友请求 / 加群请求的同意或拒绝。
 * Sender 翻译为 set_friend_add_request / set_group_add_request（官方平台按钮 ack 为演进项）。
 *
 * @param requestFlag 平台处理凭据（来自入方向 Interaction 的 ext.qq.request_flag）
 * @param approve     true 同意、false 拒绝
 * @param reason      拒绝理由，可空
 */
public record SendInteractionReplyPayload(
        String requestFlag,
        boolean approve,
        String reason
) implements EnvelopePayload {
}
