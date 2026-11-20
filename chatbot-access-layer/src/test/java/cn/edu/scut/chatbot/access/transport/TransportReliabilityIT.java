package cn.edu.scut.chatbot.access.transport;

import cn.edu.scut.chatbot.access.fixtures.OneBotSamples;
import cn.edu.scut.chatbot.access.inbound.AccessLayerIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护纪律：断线恢复是结构保证（宪法第三条 + quickstart 场景 C1）。
 * 协议端主动断连与停发心跳（应用层心跳超时判死）两条路径都触发退避重连，
 * 恢复后新报文继续忠实入流；Redis 停机时事件不确认但原文已留档（落流成功才算接收成功）。
 *
 * <p>等待全部用 FakeNapCat 的连接计数与入流内容这类可观察结果——判死等待期间不推探测帧
 * （探测帧会刷新活跃时刻，判死永远不触发）。
 */
class TransportReliabilityIT extends AccessLayerIntegrationTestBase {

    @Autowired
    private OneBotWsClient wsClient;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 心跳判死用短间隔：协议端 800ms 心跳 → 3×800ms=2.4s 静默即判死（不等真实 30s 档）
     */
    @DynamicPropertySource
    static void fastHeartbeatProperties(DynamicPropertyRegistry registry) {
        registry.add("access.transport.heartbeat.fallback-heart-interval-millis", () -> "800");
    }

    @Test
    void 协议端主动断连后接入层退避重连并继续处理新报文() throws Exception {
        // 本类的判死阈值被调到 3×800ms（类级 DynamicProperty），先开同频心跳防连接被判死循环
        // 干扰断言；本用例验证的是「协议端主动断连」路径，判死路径由下一用例专门验证
        fakeNapCat().startHeartbeat(10000, 800);
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> wsClient.isReady() && fakeNapCat().openConnectionCount() == 1);

        // 先推一条基线消息确认链路健康
        fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-text"));
        awaitEnvelopeInStream("1110001");

        // 协议端主动断连（模拟协议端重启 / 网络中断）
        fakeNapCat().closeAllConnections();
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> fakeNapCat().openConnectionCount() == 0);

        // 退避重连：连接回来且就绪
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> wsClient.isReady() && fakeNapCat().openConnectionCount() == 1);

        // 恢复后继续处理新报文
        fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-image-no-url"));
        awaitEnvelopeInStream("1110003");
        fakeNapCat().stopHeartbeat();
    }

    @Test
    void 协议端停发心跳触发应用层判死并重连恢复() throws Exception {
        // 先开 800ms 心跳让连接就绪稳定：接入层从心跳帧学得 interval=800（判死阈值 3×800ms）；
        // 若上一用例刚触发过判死，连接还在退避重连循环中，心跳一到即恢复
        fakeNapCat().startHeartbeat(10000, 800);
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> wsClient.isReady() && fakeNapCat().openConnectionCount() == 1);

        // 停发心跳后纯静默等待判死：期间不推任何帧（探测帧会刷新活跃时刻）
        fakeNapCat().stopHeartbeat();
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> fakeNapCat().openConnectionCount() == 0);

        // 判死触发主动断开 → 退避重连 → 连接恢复
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> wsClient.isReady() && fakeNapCat().openConnectionCount() == 1);

        // 恢复的可观察标志：新报文继续忠实入流
        fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-text"));
        awaitEnvelopeInStream("1110001");
        fakeNapCat().stopHeartbeat();
    }

    @Test
    void redis停机时事件不确认且原文已留档() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);
        fakeNapCat().startHeartbeat(10000, 5000);

        long archivedBefore = archivedRowCount();

        pauseRedis();
        try {
            // Redis 停机期间平台推送：XADD 必然失败——原文已留档、入流不出现该信封（不确认）
            fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-anonymous"));

            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(archivedRowCount()).isGreaterThan(archivedBefore));
            assertThat(envelopeExistsInStream("1110002"))
                    .as("Redis 停机期间事件不得进流（不确认，等平台重推）")
                    .isFalse();
        } finally {
            resumeRedis();
        }

        // Redis 恢复后新报文正常入流
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-text"));
            return envelopeExistsInStream("1110001");
        });
        fakeNapCat().stopHeartbeat();
    }

    // ===== 观察辅助 =====

    private long archivedRowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM raw_event_log", Long.class);
        return count == null ? 0 : count;
    }

    private boolean envelopeExistsInStream(String dedupKey) {
        try {
            JsonNode latest = latestInboundEnvelope();
            return latest != null && dedupKey.equals(latest.path("dedupKey").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private void awaitEnvelopeInStream(String dedupKey) {
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> envelopeExistsInStream(dedupKey));
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
