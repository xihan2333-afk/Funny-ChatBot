package cn.edu.scut.chatbot.access.infrastructure;

import cn.edu.scut.chatbot.access.sender.OutboundConsumer;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * pending 重投扫描器（契约 redis-streams.md 第 4 节 + research R5 决策）：
 * 周期 {@code XPENDING 出流 access-layer IDLE 60000 - + 64} 取超时空闲消息，
 * 逐条 {@code XCLAIM} 认领后走与正常消费<b>同一条分发路径</b>（OutboundConsumer.redispatch）——
 * 不用 XAUTOCLAIM（不锁 Redis 6.2 下限、保留逐条 idle 可见性）。
 *
 * <p>投递次数超过告警线的记 adapter.stream.poison ERROR 日志后<b>仍重投</b>：
 * 接入层无死信队列，毒消息持续暴露给观测是刻意设计（异常藏进死信队列等于静默丢事件）。
 */
@Component
public class PendingRescanner {

    private static final Logger log = LoggerFactory.getLogger(PendingRescanner.class);

    /**
     * 单次扫描最多认领的消息数（与契约的 64 对齐）
     */
    private static final long SCAN_BATCH = 64;

    private final StreamGateway streamGateway;
    private final OutboundConsumer outboundConsumer;
    private final String outStreamKey;
    private final String consumerGroup;
    private final String consumerName;
    private final long pendingIdleThresholdMillis;
    private final long poisonDeliveryCount;

    public PendingRescanner(StreamGateway streamGateway,
                            OutboundConsumer outboundConsumer,
                            @Value("${access.stream.out-key}") String outStreamKey,
                            @Value("${access.stream.consumer-group}") String consumerGroup,
                            @Value("${access.stream.consumer-name}") String consumerName,
                            @Value("${access.stream.pending-idle-threshold-millis:60000}") long pendingIdleThresholdMillis,
                            @Value("${access.stream.poison-delivery-count:5}") long poisonDeliveryCount) {
        this.streamGateway = streamGateway;
        this.outboundConsumer = outboundConsumer;
        this.outStreamKey = outStreamKey;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.pendingIdleThresholdMillis = pendingIdleThresholdMillis;
        this.poisonDeliveryCount = poisonDeliveryCount;
    }

    /**
     * 周期扫描（默认 60 秒，契约第 4 节）：XPENDING 空闲筛选 → 逐条 XCLAIM → 同一分发路径
     */
    @Scheduled(fixedDelayString = "${access.stream.pending-rescan-period-millis:60000}",
            initialDelayString = "${access.stream.pending-rescan-period-millis:60000}")
    public void rescan() {
        List<StreamGateway.PendingMessageView> stalePending;
        try {
            stalePending = streamGateway.pendingIdleBeyond(
                    outStreamKey, consumerGroup, pendingIdleThresholdMillis, SCAN_BATCH);
        } catch (Exception e) {
            log.error("pending 扫描失败（Redis 不可达？），本轮放弃。errorCode={}",
                    ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, e);
            return;
        }
        for (StreamGateway.PendingMessageView pending : stalePending) {
            if (pending.deliveryCount() > poisonDeliveryCount) {
                log.error("pending 消息投递次数已达 {} 次（超过告警线 {}），疑似毒消息——仍继续重投，持续暴露。"
                                + "errorCode={}，recordId={}",
                        pending.deliveryCount(), poisonDeliveryCount,
                        ErrorCodes.ADAPTER_STREAM_POISON, pending.recordId());
            }
            try {
                List<StreamGateway.StreamMessage> claimed = streamGateway.claimIdle(
                        outStreamKey, consumerGroup, consumerName,
                        pendingIdleThresholdMillis, List.of(pending.recordId()));
                for (StreamGateway.StreamMessage message : claimed) {
                    outboundConsumer.redispatch(message);
                }
            } catch (Exception e) {
                // 单条失败不阻断同批其余消息（失败条目下一轮扫描自然再出现）
                log.error("pending 消息认领失败，留待下一轮。errorCode={}，recordId={}",
                        ErrorCodes.ADAPTER_STREAM_WRITE_FAILED, pending.recordId(), e);
            }
        }
    }
}
