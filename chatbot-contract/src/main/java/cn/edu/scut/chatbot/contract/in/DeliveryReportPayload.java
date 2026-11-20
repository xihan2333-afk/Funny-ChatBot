package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * 投递回执载荷（data-model.md 第 6 节）：出事件投递的唯一反馈通道，作为入事件回流。
 *
 * <p>回执是事实登记（过去式），一次投递一条回执；pending 重投会产生第二条同 outboxId
 * 回执，Agent 侧按 (outboxId + attemptedAt) 取最新。无「发出即忘」路径。
 *
 * @param outboxId          关联的出事件（Agent 结算键），非空
 * @param platformMessageId 平台侧 message_id，发送成功时非空、失败时为 null
 * @param status            投递结果短码，取值见 {@link DeliveryStatusCodes}
 * @param failureKind       失败分类短码，取值见 {@link DeliveryFailureKindCodes}；status 非 failed 时为 null
 * @param failureDetail     平台原始错误信息（截断至 512 字符），可空
 * @param quotaRemaining    平台返回的额度余量（OneBot 无此语义，官方平台演进项），可空
 * @param attemptedAt       尝试时刻（epoch 毫秒）
 * @param retried           是否经过接入层瞬时重试后仍失败
 */
public record DeliveryReportPayload(
        String outboxId,
        String platformMessageId,
        String status,
        String failureKind,
        String failureDetail,
        String quotaRemaining,
        Long attemptedAt,
        boolean retried
) implements EnvelopePayload {

    /**
     * failureDetail 的截断上限（字符）
     */
    public static final int FAILURE_DETAIL_MAX_LENGTH = 512;
}
