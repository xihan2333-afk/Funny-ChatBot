package cn.edu.scut.chatbot.access.observability;

import cn.edu.scut.chatbot.access.codec.EnvelopeCodec;
import cn.edu.scut.chatbot.access.fixtures.OneBotSamples;
import cn.edu.scut.chatbot.access.inbound.AccessLayerIntegrationTestBase;
import cn.edu.scut.chatbot.access.transport.OneBotWsClient;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护纪律：traceId 贯穿「平台进 → 入流 → 出事件 → 回执」全程（quickstart US5 验收口径）。
 * 入流信封 traceContext 为合法 W3C traceparent；出事件回执携带与出事件一致的 traceId；
 * 健康检查端点对协议端断线自愈属正常态不判死容器（部署章 compose 判活口径）。
 */
class TraceContinuityIT extends AccessLayerIntegrationTestBase {

    /**
     * W3C traceparent 格式：version-traceId(32 hex)-spanId(16 hex)-flags
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final String OUT_STREAM_KEY = "chatbot:out:onebot-v11";
    private static final String OUTBOUND_TRACE =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    private OneBotWsClient wsClient;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private TestRestTemplate restTemplate;

    private final EnvelopeCodec envelopeCodec = new EnvelopeCodec();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 群消息入流信封携带合法W3C_traceparent() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-text"));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode envelope = latestInboundEnvelope();
            assertThat(envelope).isNotNull();
            String traceContext = envelope.path("traceContext").asText(null);
            assertThat(traceContext).as("入流信封应携带 traceContext").isNotNull();
            assertThat(TRACEPARENT_PATTERN.matcher(traceContext).matches())
                    .as("traceContext 应为合法 W3C traceparent 格式，实际：%s", traceContext)
                    .isTrue();
        });
    }

    @Test
    void 出事件回执携带与出事件一致的traceId() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        // 向出流写一条带 traceparent 的出事件（模拟 agent-core 生产行为）
        EventEnvelope outbound = new EventEnvelope(
                null, "3849281194743619900", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                new SendGroupMessagePayload(List.of(new TextPart(TextPart.TYPE_TEXT, "追我的 trace")), null),
                null, null, Map.of(), null, OUTBOUND_TRACE);
        redisTemplate.opsForStream().add(
                org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                        .in(OUT_STREAM_KEY)
                        .ofMap(Map.of("envelope", envelopeCodec.serialize(outbound))));

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode report = latestInboundEnvelope();
            assertThat(report.path("type").asText()).isEqualTo("delivery_report");
            // 回执沿用原出事件的 traceContext——全程一个 traceId（traceId 段逐字符一致）
            assertThat(report.path("traceContext").asText()).isEqualTo(OUTBOUND_TRACE);
        });
    }

    @Test
    void 健康检查端点返回up且不被协议端断连拖垮() throws Exception {
        // 接入层对协议端断线自愈属正常态（重连循环在跑），不判死容器
        org.springframework.http.ResponseEntity<String> health =
                restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
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
