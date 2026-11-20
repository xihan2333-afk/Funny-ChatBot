package cn.edu.scut.chatbot.access.inbound;

import cn.edu.scut.chatbot.access.fixtures.FakeNapCat;
import cn.edu.scut.chatbot.access.fixtures.OneBotSamples;
import cn.edu.scut.chatbot.access.transport.OneBotWsClient;
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
 * 守护纪律：群消息忠实入流（quickstart 场景 A / SC-001 翻译保真）。
 * 一条平台报文 → 一行留档 + 一条入流信封，信息无损：标准字段全量翻译、
 * rawRef 回指留档行、平台特有语义在 extension 可读。真容器 + 假协议端按剧本演。
 */
class AccessInboundIT extends AccessLayerIntegrationTestBase {

    @Autowired
    private OneBotWsClient wsClient;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 混合群消息报文忠实入流且留档行与rawRef互相回指() throws Exception {
        // 等待接入层连接就绪（lifecycle.connect 到达 + 自检完成）
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        // 推送录制的 OneBot 报文（@ + 文本 + 图片混合段，array 格式）
        fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-at-text-image"));

        // 断言①：入流出现结构化信封
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode envelope = latestInboundEnvelope();
            assertThat(envelope).isNotNull();
            assertThat(envelope.path("type").asText()).isEqualTo("group_message");
            assertThat(envelope.path("dedupKey").asText()).isEqualTo("1234567");
            assertThat(envelope.path("botId").asText()).isEqualTo("10000");
            assertThat(envelope.path("groupId").asText()).isEqualTo("123456789");
            assertThat(envelope.path("userId").asText()).isEqualTo("10001000");
            assertThat(envelope.path("occurredAt").asLong()).isEqualTo(1717200000000L);

            JsonNode payload = envelope.path("payload");
            assertThat(payload.path("parts")).hasSize(3);
            assertThat(payload.path("parts").get(0).path("part").asText()).isEqualTo("at");
            assertThat(payload.path("parts").get(0).path("targetUserId").asText()).isEqualTo("10000");
            assertThat(payload.path("parts").get(1).path("text").asText()).isEqualTo(" 看看这张图");
            JsonNode image = payload.path("parts").get(2);
            assertThat(image.path("part").asText()).isEqualTo("image");
            // 图片段 transferStatus 有值（转存尝试已走三分支之一，一次成型）
            assertThat(image.path("transferStatus").asText()).isNotBlank();
            assertThat(payload.path("sender").path("nickname").asText()).isEqualTo("滑稽");
            assertThat(payload.path("rawText").asText())
                    .isEqualTo("[CQ:at,qq=10000] 看看这张图[CQ:image,file=6B4DE3DF.jpg]");

            assertThat(envelope.path("eventId").asText()).isNotBlank();
            assertThat(envelope.path("receivedAt").isNumber()).isTrue();
        });

        // 断言②：留档表有 direction=in 与 event_type=message.group 的原文行
        JsonNode envelope = latestInboundEnvelope();
        String rawEventId = envelope.path("rawRef").path("rawEventId").asText();
        assertThat(rawEventId).isNotBlank();

        var archived = jdbcTemplate.queryForMap(
                "SELECT id, platform, bot_id, direction, event_type, raw_payload FROM raw_event_log WHERE id = ?",
                Long.parseLong(rawEventId));
        assertThat(archived.get("direction")).isEqualTo("in");
        assertThat(archived.get("event_type")).isEqualTo("message.group");
        assertThat(archived.get("platform")).isEqualTo("onebot-v11");
        assertThat(String.valueOf(archived.get("raw_payload"))).contains("\"message_id\": 1234567");

        // 断言③：CQ 码 string 格式报文（配置漂移场景）同样收敛为同一内部表示
        fakeNapCat().pushTextFrame(OneBotSamples.load("group-message-cq-string"));
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode cqEnvelope = latestInboundEnvelope();
            assertThat(cqEnvelope.path("dedupKey").asText()).isEqualTo("2233445");
            JsonNode parts = cqEnvelope.path("payload").path("parts");
            assertThat(parts.get(0).path("text").asText()).isEqualTo("你好");
            // CQ 码四字符转义已还原：file 值是 a&b.jpg 而非 a&amp;b.jpg
            JsonNode cqImage = parts.get(2);
            assertThat(cqImage.path("fileName").asText()).isEqualTo("a&b.jpg");
            assertThat(cqImage.path("originalUrl").asText()).isEqualTo("https://cdn.example.com/img?x=1&y=2");
            assertThat(parts.get(3).path("targetUserId").asText()).isEqualTo("all");
        });
    }

    @Test
    void 畸形半截帧兜底收敛raw_event且原文留档() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        fakeNapCat().pushTextFrame(OneBotSamples.load("malformed-half-frame"));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode envelope = latestInboundEnvelope();
            // null 先行断言（AssertionError 才会被 Awaitility 重试；直接 .path() 首轮轮询 NPE 即失败）
            assertThat(envelope).isNotNull();
            assertThat(envelope.path("type").asText()).isEqualTo("raw_event");
            assertThat(envelope.path("payload").path("reason").asText()).isEqualTo("illegal_json");
            assertThat(envelope.path("payload").path("rawRef").path("rawEventId").asText()).isNotBlank();
        });
    }

    /**
     * 读入流最新一条信封（XRANGE 全量取最后一条解析 envelope 字段）
     */
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
