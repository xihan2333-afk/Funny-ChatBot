package cn.edu.scut.chatbot.infrastructure.redis;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.bootstrap.GroupSerialExecutor;
import cn.edu.scut.chatbot.infrastructure.EnvelopeCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 入流消费者（契约 redis-streams-agent-core.md 第 1 节）：启动 XGROUP CREATE（MKSTREAM，
 * 已存在忽略）、命名虚拟线程 XREADGROUP 阻塞批量读、消费线程只搬运——按 (botId, groupId)
 * submit 到 GroupSerialExecutor，业务完成才 XACK（至少一次语义）、@Scheduled XPENDING 扫描
 * 60 秒空闲 XCLAIM 重投。与接入层 Sender 消费出流完全同构。
 *
 * <p>重复投递由消费端 dedupKey 幂等兜住（trigger_event uk_dedup 拒插）——本组件不感知业务。
 *
 * <p>毒消息处理：反序列化失败的信封记 ERROR（带 errorCode 与 recordId）并直接 XACK 跳过
 * ——毒消息不确认会永远重投（无死信队列的既定取舍：暴露给观测、不阻塞流）。
 */
@Component
public class InboundStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(InboundStreamConsumer.class);

    private final RedisStreamGateway gateway;
    private final GroupSerialExecutor serialExecutor;
    private final EnvelopeCodec codec;

    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final long blockMillis;
    private final long readCount;
    private final long pendingIdleThresholdMillis;
    private final long poisonDeliveryCount;

    /**
     * 消费回调：业务分发钩子（engine 的 InboundDispatch 注入）
     */
    private final InboundHandler handler;

    /**
     * 消费循环停止位（优雅停机）
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 入流消息处理回调：实现方在 GroupSerialExecutor 泵内处理单条信封（同群串行）。
     * 抛异常视同处理失败——消息不确认，等 pending 重投。
     */
    public interface InboundHandler {

        void handle(EventEnvelope envelope) throws Exception;
    }

    public InboundStreamConsumer(
            RedisStreamGateway gateway,
            GroupSerialExecutor serialExecutor,
            EnvelopeCodec codec,
            InboundHandler handler,
            @Value("${agent.stream.in-key}") String streamKey,
            @Value("${agent.stream.consumer-group}") String groupName,
            @Value("${agent.stream.consumer-name}") String consumerName,
            @Value("${agent.stream.block-millis:55000}") long blockMillis,
            @Value("${agent.stream.read-count:16}") long readCount,
            @Value("${agent.stream.pending-idle-threshold-millis:60000}") long pendingIdleThresholdMillis,
            @Value("${agent.stream.poison-delivery-count:5}") long poisonDeliveryCount) {
        this.gateway = gateway;
        this.serialExecutor = serialExecutor;
        this.codec = codec;
        this.handler = handler;
        this.streamKey = streamKey;
        this.groupName = groupName;
        this.consumerName = consumerName;
        this.blockMillis = blockMillis;
        this.readCount = readCount;
        this.pendingIdleThresholdMillis = pendingIdleThresholdMillis;
        this.poisonDeliveryCount = poisonDeliveryCount;
    }

    /**
     * 启动：建组 + 起命名常驻消费虚拟线程
     */
    @PostConstruct
    public void start() {
        gateway.ensureConsumerGroup(streamKey, groupName);
        running.set(true);
        Thread.ofVirtual().name("inbound-consumer-" + consumerName).start(this::consumeLoop);
        log.info("入流消费者启动 stream={} group={}", streamKey, groupName);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
    }

    /**
     * 消费主循环：批量读 → 逐条分发（submit 到群串行泵并等待完成）→ 批量 XACK。
     * 读失败退避重试（Redis 抖动不拖死循环）。
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                List<RedisStreamGateway.StreamMessage> batch =
                        gateway.readNewMessages(streamKey, groupName, consumerName, readCount, blockMillis);
                if (batch.isEmpty()) {
                    continue;
                }
                for (RedisStreamGateway.StreamMessage message : batch) {
                    dispatchOne(message);
                }
            } catch (Exception e) {
                log.error("入流读取失败 errorCode=engine.stream.read_failed，退避 1 秒重试", e);
                sleepQuietly(1000);
            }
        }
    }

    /**
     * 单条分发：反序列化 → 按群 submit（业务完成才返回）→ XACK。
     * 坏信封（毒消息）直接确认跳过并告警。
     */
    private void dispatchOne(RedisStreamGateway.StreamMessage message) {
        EventEnvelope envelope;
        try {
            envelope = codec.deserialize(message.envelope());
        } catch (Exception e) {
            // 毒消息：结构坏或未知 type，确认跳过（不确认会无限重投）
            log.error("入流毒消息跳过 errorCode=engine.intake.poison_envelope recordId={}",
                    message.recordId(), e);
            gateway.acknowledge(streamKey, groupName, List.of(message.recordId()));
            return;
        }
        try {
            // 消费线程只搬运：业务在同群串行泵内执行，等完成（异常则不确认，等重投）
            serialExecutor.submit(envelope.botId(), safeGroupId(envelope), () -> {
                try {
                    handler.handle(envelope);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).join();
            gateway.acknowledge(streamKey, groupName, List.of(message.recordId()));
        } catch (Exception e) {
            log.error("入流消息处理失败（不确认等重投） errorCode=engine.stream.handle_failed "
                    + "recordId={} dedupKey={}", message.recordId(), envelope.dedupKey(), e);
            // 不 XACK：消息留在 pending，等下方重投扫描拾起
        }
    }

    /**
     * pending 重投扫描：空闲超阈值的消息 XCLAIM 认领后走同一条分发路径；
     * 投递次数超限记毒消息告警（仍继续重投——无死信队列，持续暴露给观测）
     */
    @Scheduled(fixedDelayString = "${agent.stream.pending-rescan-period-millis:60000}")
    public void rescanPending() {
        if (!running.get()) {
            return;
        }
        try {
            List<RedisStreamGateway.PendingMessageView> idle = gateway.pendingIdleBeyond(
                    streamKey, groupName, pendingIdleThresholdMillis, 100);
            if (idle.isEmpty()) {
                return;
            }
            List<String> recordIds = idle.stream()
                    .peek(view -> {
                        if (view.deliveryCount() >= poisonDeliveryCount) {
                            log.error("入流 pending 毒消息告警 errorCode=engine.stream.poison "
                                    + "recordId={} deliveryCount={}", view.recordId(), view.deliveryCount());
                        }
                    })
                    .map(RedisStreamGateway.PendingMessageView::recordId)
                    .toList();
            List<RedisStreamGateway.StreamMessage> reclaimed =
                    gateway.claimIdle(streamKey, groupName, consumerName, pendingIdleThresholdMillis, recordIds);
            for (RedisStreamGateway.StreamMessage message : reclaimed) {
                dispatchOne(message);
            }
        } catch (Exception e) {
            log.error("入流 pending 重投扫描失败 errorCode=engine.stream.rescan_failed", e);
        }
    }

    /**
     * groupId 为空的信封（全局事件）落到全局串行键——保证有键可分
     */
    private String safeGroupId(EventEnvelope envelope) {
        return envelope.groupId() != null && !envelope.groupId().isBlank()
                ? envelope.groupId() : "__global__";
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
