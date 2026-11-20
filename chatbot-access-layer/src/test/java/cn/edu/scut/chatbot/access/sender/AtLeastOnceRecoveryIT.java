package cn.edu.scut.chatbot.access.sender;

import cn.edu.scut.chatbot.access.codec.EnvelopeCodec;
import cn.edu.scut.chatbot.access.fixtures.FakeNapCat;
import cn.edu.scut.chatbot.access.inbound.AccessLayerIntegrationTestBase;
import cn.edu.scut.chatbot.access.transport.OneBotWsClient;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.DeliveryStatusCodes;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护纪律：至少一次与崩溃恢复（quickstart 场景 C2 / 宪法第三条）。
 * 消费者「读到达但未 ACK」时消失（用独立 consumer 名读走后放弃，语义等价于进程死亡），
 * pending 重投在空闲阈值后 XCLAIM 认领、假协议端最终收到消息、回执正常回流、重试后 XACK。
 * 负向断言：同一出事件重复投递两次时接入层不拒绝不跳过（去重是 Agent 消费端职责，宪法第二条）。
 */
class AtLeastOnceRecoveryIT extends AccessLayerIntegrationTestBase {

    private static final String OUT_STREAM_KEY = "chatbot:out:onebot-v11";
    private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    private OneBotWsClient wsClient;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final EnvelopeCodec envelopeCodec = new EnvelopeCodec();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 收窄重投节奏：空闲阈值与扫描周期都压到秒级（真实档 60 秒，测试不等真实时长）
     */
    @DynamicPropertySource
    static void fastPendingProperties(DynamicPropertyRegistry registry) {
        registry.add("access.stream.pending-idle-threshold-millis", () -> "1500");
        registry.add("access.stream.pending-rescan-period-millis", () -> "1000");
    }

    @Test
    void 已读未确认的出事件在消费者消失后被XCLAIM重投并完成闭环() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        // 写一条出事件
        appendOutbound("3849281194743619001", "123456789", "崩溃前的话");

        // 模拟崩溃：以独立消费者名读走出流消息后「消失」（不 XACK——消息留在 pending）
        readAsDyingConsumer();

        // pending 重投（1.5 秒空闲阈值 + 1 秒扫描周期）：消息被 XCLAIM 认领并重新投递
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            FakeNapCat.CapturedApiCall call = fakeNapCat().capturedCalls().stream()
                    .filter(c -> "send_group_msg".equals(c.action()) && c.rawFrame().contains("崩溃前的话"))
                    .findFirst().orElse(null);
            assertThat(call).as("重投后假协议端应收到该调用").isNotNull();
        });

        // 回执回流且最终 XACK（pending 归零）
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode report = latestInboundEnvelope();
            assertThat(report.path("type").asText()).isEqualTo("delivery_report");
            assertThat(report.path("outboxId").asText()).isEqualTo("3849281194743619001");
            assertThat(report.path("payload").path("status").asText()).isEqualTo(DeliveryStatusCodes.SUCCESS);
        });
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(pendingCount()).isZero());
    }

    @Test
    void 同一出事件被重复投递两次时接入层不拒绝不跳过() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        // 两次写入同 outboxId 的出事件（模拟平台重推 / pending 重投产生的重复）
        appendOutbound("3849281194743619002", "123456789", "重复的话");
        appendOutbound("3849281194743619002", "123456789", "重复的话");

        // 两次都到达协议端（接入层无去重状态、无幂等拦截）
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            long deliveries = fakeNapCat().capturedCalls().stream()
                    .filter(c -> "send_group_msg".equals(c.action()) && c.rawFrame().contains("重复的话"))
                    .count();
            assertThat(deliveries).isEqualTo(2);
        });

        // 两条回执都回流（Agent 消费端按 (outboxId + attemptedAt) 幂等取最新——那是 Agent 的职责）
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            long reports = redisTemplate.opsForStream()
                    .range(IN_STREAM_KEY, org.springframework.data.domain.Range.unbounded()).stream()
                    .map(record -> String.valueOf(record.getValue().get("envelope")))
                    .filter(envelope -> envelope.contains("\"outboxId\":\"3849281194743619002\"")
                            && envelope.contains("delivery_report"))
                    .count();
            assertThat(reports).isGreaterThanOrEqualTo(2);
        });
    }

    // ===== 辅助 =====

    private void appendOutbound(String outboxId, String groupId, String text) {
        EventEnvelope envelope = new EventEnvelope(
                null, outboxId, null, PlatformCodes.ONEBOT_V11, "10000", groupId, null,
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                new SendGroupMessagePayload(List.of(new TextPart(TextPart.TYPE_TEXT, text)), null),
                null, null, Map.of(), null, TRACE);
        redisTemplate.opsForStream().add(
                org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                        .in(OUT_STREAM_KEY)
                        .ofMap(Map.of("envelope", envelopeCodec.serialize(envelope))));
    }

    /**
     * 模拟崩溃消费者：以一次性消费者名 XREADGROUP 读走全部新消息后不再出现（不 XACK）
     */
    private void readAsDyingConsumer() {
        redisTemplate.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from("access-layer", "crashed-consumer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(16),
                StreamOffset.create(OUT_STREAM_KEY,
                        org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
    }

    private long pendingCount() {
        return redisTemplate.opsForStream()
                .pending(OUT_STREAM_KEY, "access-layer")
                .getTotalPendingMessages();
    }

    private JsonNode latestInboundEnvelope() throws Exception {
        List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> records =
                redisTemplate.opsForStream().range(IN_STREAM_KEY,
                        org.springframework.data.domain.Range.unbounded());
        if (records == null || records.isEmpty()) {
            return null;
        }
        Object envelope = records.get(records.size() - 1).getValue().get("envelope");
        return envelope == null ? null : json.readTree(String.valueOf(envelope));
    }
}
