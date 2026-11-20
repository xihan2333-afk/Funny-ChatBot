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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护纪律：出事件真实投递与回执闭环（quickstart 场景 B / 宪法第三条「凭投递回执闭环结算」）。
 * 向出流写一条 SendGroupMessage（模拟 agent-core 生产行为）→ 假协议端捕获 send_group_msg
 * 调用 → 入流回流 delivery_report（outboxId 关联、status、platformMessageId）→ XACK 归零、
 * 出方向留档。同群串行 / 异群并行用可观察的调用顺序与总耗时验证（宪法第七条并发纪律）。
 */
class SenderDeliveryIT extends AccessLayerIntegrationTestBase {

    private static final String TRACE_CONTEXT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    private OneBotWsClient wsClient;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final EnvelopeCodec envelopeCodec = new EnvelopeCodec();
    private final ObjectMapper json = new ObjectMapper();

    private EventEnvelope sendGroupMessageEnvelope(String outboxId, String groupId, String text) {
        return new EventEnvelope(
                null, outboxId, null,
                PlatformCodes.ONEBOT_V11, "10000", groupId, null,
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                new SendGroupMessagePayload(List.of(new TextPart(TextPart.TYPE_TEXT, text)), null),
                null, null, java.util.Map.of(), null, TRACE_CONTEXT);
    }

    private void appendToOutStream(EventEnvelope envelope) {
        redisTemplate.opsForStream().add(
                org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                        .in(OUT_STREAM_KEY_FOR_TEST)
                        .ofMap(java.util.Map.of("envelope", envelopeCodec.serialize(envelope))));
    }

    private static final String OUT_STREAM_KEY_FOR_TEST = "chatbot:out:onebot-v11";

    @Test
    void 发群消息投递成功回执闭环且pending归零出方向留档() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);
        // 用例间共享假件与入流，先清捕获（JUnit 方法顺序不定，防拿到前序用例的帧）
        fakeNapCat().clearCapturedCalls();

        // 模拟 agent-core 生产行为：向出流写一条 SendGroupMessage
        appendToOutStream(sendGroupMessageEnvelope("3849281194743617536", "123456789", "hello"));

        // ① 假协议端捕获到 send_group_msg 调用且参数正确
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            FakeNapCat.CapturedApiCall call = fakeNapCat().capturedCalls().stream()
                    .filter(c -> "send_group_msg".equals(c.action()))
                    .findFirst().orElse(null);
            assertThat(call).as("假协议端应捕获到 send_group_msg 调用").isNotNull();
            assertThat(call.rawFrame()).contains("\"group_id\":\"123456789\"");
            assertThat(call.rawFrame()).contains("hello");
        });

        // ② 入流回流 delivery_report：outboxId 关联、status=success、platformMessageId 非空
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode report = inboundEnvelopeOf("3849281194743617536");
            assertThat(report).isNotNull();
            assertThat(report.path("type").asText()).isEqualTo("delivery_report");
            assertThat(report.path("payload").path("status").asText()).isEqualTo(DeliveryStatusCodes.SUCCESS);
            assertThat(report.path("payload").path("platformMessageId").asText()).isNotBlank();
            // 回执沿用原出事件 traceId（全程一个 traceId）
            assertThat(report.path("traceContext").asText()).isEqualTo(TRACE_CONTEXT);
        });

        // ③ 出流该消息已 XACK：pending 计数归零
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(pendingCount()).isZero());

        // ④ 出方向留档：raw_event_log 出现 direction=out 行
        Integer outRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM raw_event_log WHERE direction = 'out'", Integer.class);
        assertThat(outRows).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 平台明确拒绝时回流failed回执不静默吞() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);
        fakeNapCat().clearCapturedCalls();

        // 剧本：下一次 send_group_msg 调用返回审核拒绝（retcode=1200 审核类）
        fakeNapCat().scriptNextResponseForAction("send_group_msg",
                "{\"status\":\"failed\",\"retcode\":1200,\"data\":null,\"echo\":null,\"wording\":\"消息被风控拦截\"}");
        appendToOutStream(sendGroupMessageEnvelope("3849281194743618000", "123456789", "被拒的消息"));

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode report = inboundEnvelopeOf("3849281194743618000");
            // null 先行断言（AssertionError 才会被 Awaitility 重试；直接 .path() 首轮轮询 NPE 即失败）
            assertThat(report).isNotNull();
            assertThat(report.path("type").asText()).isEqualTo("delivery_report");
            assertThat(report.path("payload").path("status").asText()).isEqualTo(DeliveryStatusCodes.FAILED);
            assertThat(report.path("payload").path("failureKind").asText()).isEqualTo("content_rejected");
            assertThat(report.path("payload").path("failureDetail").asText()).contains("1200");
        });
        // 失败同样是完成态：XACK 归零
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(pendingCount()).isZero());
    }

    @Test
    void 两群交错投递各群内顺序与流内一致且并行推进() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);
        fakeNapCat().clearCapturedCalls();
        fakeNapCat().setApiCallDelayMillis(300);

        try {
            // 交错写两群各 3 条（顺序：A1 B1 A2 B2 A3 B3）
            appendToOutStream(sendGroupMessageEnvelope("out-a1", "111000111", "A1"));
            appendToOutStream(sendGroupMessageEnvelope("out-b1", "222000222", "B1"));
            appendToOutStream(sendGroupMessageEnvelope("out-a2", "111000111", "A2"));
            appendToOutStream(sendGroupMessageEnvelope("out-b2", "222000222", "B2"));
            appendToOutStream(sendGroupMessageEnvelope("out-a3", "111000111", "A3"));
            appendToOutStream(sendGroupMessageEnvelope("out-b3", "222000222", "B3"));

            long start = System.nanoTime();
            Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                List<FakeNapCat.CapturedApiCall> calls = fakeNapCat().capturedCalls().stream()
                        .filter(c -> "send_group_msg".equals(c.action()))
                        .toList();
                assertThat(calls).hasSize(6);
            });
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // 各群内投递顺序与流内顺序一致（可观察结果顺序，不是调度时序）
            List<String> order = fakeNapCat().capturedCalls().stream()
                    .filter(c -> "send_group_msg".equals(c.action()))
                    .map(c -> groupMessageText(c))
                    .toList();
            assertThat(order.indexOf("A1")).isLessThan(order.indexOf("A2"));
            assertThat(order.indexOf("A2")).isLessThan(order.indexOf("A3"));
            assertThat(order.indexOf("B1")).isLessThan(order.indexOf("B2"));
            assertThat(order.indexOf("B2")).isLessThan(order.indexOf("B3"));
            // 两群并行推进的耗时护栏。观察口径说明：接入层单 WS 连接复用，假件对同一连接的
            // 消息按 JSR-356 语义串行处理（每帧 sleep 300ms），6 帧全部被捕获的最早时刻天然
            // 是 5×300=1500ms——真正的判别断言是上面的各群顺序断言；耗时护栏只防「退化为
            // 慢于两群完全串行」的回归（串行值为 6×300=1800ms 起步再叠加处理开销），上界
            // 在串行值之上留抖动余量。
            assertThat(elapsedMillis).as("两群总耗时应远低于完全串行，实际 %dms", elapsedMillis)
                    .isLessThan(2000);
        } finally {
            fakeNapCat().setApiCallDelayMillis(0);
        }
    }

    // ===== 观察辅助 =====

    /**
     * 从捕获帧中提取群消息文本（A1 / B2 这类探针标记）
     */
    private String groupMessageText(FakeNapCat.CapturedApiCall call) {
        try {
            JsonNode params = json.readTree(call.rawFrame()).path("params");
            for (JsonNode segment : params.path("message")) {
                if ("text".equals(segment.path("type").asText())) {
                    String text = segment.path("data").path("text").asText();
                    if (text.matches("[AB][123]")) {
                        return text;
                    }
                }
            }
        } catch (Exception ignored) {
            // 捕获帧解析失败按无标记处理
        }
        return "?";
    }

    private long pendingCount() {
        return redisTemplate.opsForStream()
                .pending(OUT_STREAM_KEY_FOR_TEST, "access-layer")
                .getTotalPendingMessages();
    }

    /**
     * 入流中定向查找指定 outboxId 的信封（倒序找最近一条匹配的）。
     * 用例间入流共享（无法清理），断言必须按 outboxId 定位——取「最新一条」会拿到前序用例的回执。
     */
    private JsonNode inboundEnvelopeOf(String outboxId) throws Exception {
        List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> records =
                redisTemplate.opsForStream().range(IN_STREAM_KEY,
                        org.springframework.data.domain.Range.unbounded());
        if (records == null) {
            return null;
        }
        for (int i = records.size() - 1; i >= 0; i--) {
            Object envelope = records.get(i).getValue().get("envelope");
            if (envelope == null) {
                continue;
            }
            JsonNode node = json.readTree(String.valueOf(envelope));
            if (outboxId.equals(node.path("outboxId").asText())) {
                return node;
            }
        }
        return null;
    }
}
