package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;

/**
 * 扩展条目：投递回执（data-model 第 2 节）。默认投影形态：成功不投影；
 * 失败投一行「[你刚才的消息发送失败：原因]」。
 */
public record DeliveryReportEntry(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String outboxId,
        String status,
        String failureKind,
        String failureDetail
) implements AgentMessage {

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.DELIVERY_REPORT;
    }
}
