package cn.edu.scut.chatbot.engine.outbox;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.engine.EngineErrorCodes;
import cn.edu.scut.chatbot.exception.RetryableException;
import cn.edu.scut.chatbot.infrastructure.EnvelopeCodec;
import cn.edu.scut.chatbot.infrastructure.redis.RedisStreamGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 出流投递器（Effects 的 dispatch 处理器动作体）：按 outboxId 取行 → 组装出向信封 →
 * XADD 出流（MAXLEN ~ 100000、显式 Jackson 序列化——禁发出即忘，行先行落库是前提）。
 */
@Component
public class StreamDispatcher {

    private static final Logger log = LoggerFactory.getLogger(StreamDispatcher.class);

    private final OutboxMapper outboxMapper;
    private final RedisStreamGateway gateway;
    private final EnvelopeCodec codec;
    private final java.time.Clock clock;

    private final String outStreamKey;

    public StreamDispatcher(OutboxMapper outboxMapper,
                            RedisStreamGateway gateway,
                            EnvelopeCodec codec,
                            java.time.Clock clock,
                            @Value("${agent.stream.out-key}") String outStreamKey) {
        this.outboxMapper = outboxMapper;
        this.gateway = gateway;
        this.codec = codec;
        this.clock = clock;
        this.outStreamKey = outStreamKey;
    }

    /**
     * 投递一条出事件（H3：先条件占用 pending→dispatching 再 XADD——崩溃窗口不再产生
     * 重复发言；XADD 失败回退 pending 由 Effects 退避重投）
     */
    public void dispatch(long outboxId) {
        OutboxDO outbox = outboxMapper.selectById(outboxId);
        if (outbox == null) {
            log.error("dispatch 载荷指向不存在的 outbox 行（数据不一致，跳过） outboxId={}", outboxId);
            return;
        }
        if (!OutboxDO.STATUS_PENDING.equals(outbox.getStatus())) {
            log.debug("outbox 行已非 pending，跳过投递 outboxId={} status={}", outboxId, outbox.getStatus());
            return;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
        if (outboxMapper.claimForDispatch(outboxId, now) == 0) {
            return;   // 并发占用失败：其他投递者已领走
        }
        try {
            EventEnvelope envelope = buildOutboundEnvelope(outbox);
            String envelopeJson = codec.serialize(envelope);
            gateway.appendEnvelope(outStreamKey, envelopeJson);
            log.info("出事件投递出流 outboxId={} type={}", outboxId, outbox.getType());
        } catch (Exception e) {
            outboxMapper.revertDispatch(outboxId);
            throw new RetryableException(EngineErrorCodes.OUTBOX_DISPATCH_FAILED,
                    "出流 XADD 失败（已回退 pending，退避重投）outboxId=" + outboxId, e);
        }
    }

    /**
     * 组装出向信封：outboxId 有值、eventId/dedupKey 为 null（出方向字段形态）
     */
    private EventEnvelope buildOutboundEnvelope(OutboxDO outbox) throws Exception {
        // 载荷按出原语类型从 JSON 还原为对应 record（平铺多态由 EnvelopeCodec 反序列化路径处理）
        JsonNode payloadNode = JsonMappers.shared().readTree(outbox.getPayload());
        EnvelopePayload payload = codec.deserialize(wrapAsEnvelopeJson(outbox.getType(),
                outbox.getPayload())).payload();
        return new EventEnvelope(
                null,
                String.valueOf(outbox.getId()),
                null,
                outbox.getPlatform(),
                outbox.getBotId(),
                outbox.getGroupId(),
                extractUserId(payloadNode),
                outbox.getType(),
                payload,
                null,
                null,
                Map.of(),
                null,
                outbox.getTraceContext());
    }

    /**
     * 借 EnvelopeCodec 的平铺多态把 payload JSON 还原为对应 record
     */
    private String wrapAsEnvelopeJson(String type, String payloadJson) {
        return "{\"type\":\"" + type + "\",\"payload\":" + payloadJson + "}";
    }

    private String extractUserId(JsonNode payloadNode) {
        JsonNode userId = payloadNode.path("targetUserId");
        return userId.isMissingNode() ? null : userId.asText(null);
    }
}
