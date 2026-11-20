package cn.edu.scut.chatbot.access.inbound;

import cn.edu.scut.chatbot.access.fixtures.OneBotSamples;
import cn.edu.scut.chatbot.access.transport.OneBotWsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护纪律：全部入原语类型在入流可观测（quickstart US3 验收口径 / SC-001）。
 * 生态事件（戳一戳、入群、被禁言、被踢）各自收敛为正确原语，平台特有字段在 ext.qq.*
 * 命名空间可读；私聊与未建模扩展收敛 RawEvent——保真不丢失。
 */
class FullPrimitiveCoverageIT extends AccessLayerIntegrationTestBase {

    @Autowired
    private OneBotWsClient wsClient;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 戳一戳入群被禁言依次收敛为互动成员与bot原语且扩展字段可读() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        // 戳一戳 → Interaction(poke)，poke_type 进 ext
        fakeNapCat().pushTextFrame(OneBotSamples.load("notice-poke"));
        awaitEnvelopeOfType("interaction", envelope -> {
            assertThat(envelope.path("payload").path("subType").asText()).isEqualTo("poke");
            assertThat(envelope.path("extension").path("ext.qq.poke_type").asText()).isEqualTo("1");
        });

        // 入群 → MemberEvent(member_join)
        fakeNapCat().pushTextFrame(OneBotSamples.load("notice-group-increase"));
        awaitEnvelopeOfType("member_event", envelope ->
                assertThat(envelope.path("payload").path("subType").asText()).isEqualTo("member_join"));

        // bot 被禁言（group_ban 且 user_id=self_id）→ BotEvent(bot_muted)，时长秒数入字段
        fakeNapCat().pushTextFrame(OneBotSamples.load("notice-group-ban-self"));
        awaitEnvelopeOfType("bot_event", envelope -> {
            assertThat(envelope.path("payload").path("subType").asText()).isEqualTo("bot_muted");
            assertThat(envelope.path("payload").path("durationSeconds").asLong()).isEqualTo(600L);
        });

        // 成员被禁言（user_id≠self_id）→ MemberEvent(muted)
        fakeNapCat().pushTextFrame(OneBotSamples.load("notice-group-ban-member"));
        awaitEnvelopeOfType("member_event", envelope ->
                assertThat(envelope.path("payload").path("subType").asText()).isEqualTo("muted"));

        // 好友请求 → Interaction(friend_request)，flag 进 ext
        fakeNapCat().pushTextFrame(OneBotSamples.load("request-friend"));
        awaitEnvelopeOfType("interaction", envelope -> {
            assertThat(envelope.path("payload").path("subType").asText()).isEqualTo("friend_request");
            assertThat(envelope.path("extension").path("ext.qq.request_flag").asText()).isEqualTo("FLAG-123456");
        });
    }

    @Test
    void 私聊与未建模扩展收敛raw_event保真() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);

        // 私聊（第一版预留）→ RawEvent 保真
        fakeNapCat().pushTextFrame("""
                {"time":1717200000,"self_id":10000,"post_type":"message",
                 "message_type":"private","sub_type":"friend","message_id":888001,"user_id":30003000,
                 "message":[{"type":"text","data":{"text":"私聊你好"}}],"raw_message":"私聊你好",
                 "sender":{"user_id":30003000,"nickname":"好友"}}""");
        awaitEnvelopeOfType("raw_event", envelope ->
                assertThat(envelope.path("dedupKey").asText()).isNotBlank());

        // NapCat 未建模扩展（gray_tip 灰条）→ RawEvent 保真
        fakeNapCat().pushTextFrame("""
                {"time":1717200000,"self_id":10000,"post_type":"notice",
                 "notice_type":"gray_tip","group_id":123456789,"user_id":10001000,"content":"<灰条提示>"}""");
        awaitEnvelopeOfType("raw_event", envelope ->
                assertThat(envelope.path("payload").path("rawRef").path("rawEventId").asText()).isNotBlank());
    }

    /**
     * 等待入流出现指定 type 的最新信封并执行附加断言
     */
    private void awaitEnvelopeOfType(String expectedType, java.util.function.Consumer<JsonNode> extraAssertions) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode envelope = latestInboundEnvelope();
            assertThat(envelope).as("入流应出现信封").isNotNull();
            assertThat(envelope.path("type").asText()).isEqualTo(expectedType);
            extraAssertions.accept(envelope);
        });
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
