// 守护纪律：Qdrant 薄客户端错误统一归一点分码、检索读超时 3 秒短超时、
// api key 头只在配置了才携带、六端点请求体结构稳定（contracts/memory-retrieval.md §3）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.exception.RetryableException;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantErrorCodes;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantRestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantRestClientTest {

    private ScriptedHttpUpstream upstream;

    private QdrantRestClient client;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new ScriptedHttpUpstream();
        upstream.start();
        // HttpClientHolder 的 readTimeout 面与 Qdrant 无关（客户端自带 3 秒请求级超时），
        // SystemParamService 传 null 不影响
        client = new QdrantRestClient(new HttpClientHolder(null), upstream.baseUrl(), "it-key-1");
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    @Test
    void 建集合请求体带维度与Cosine距离_索引逐个建() {
        upstream.addScript("/collections/mem", respond("{\"result\":true,\"status\":\"ok\"}"));
        upstream.addScript("/collections/mem/index", respond("{\"result\":true}"));
        upstream.addScript("/collections/mem/index", respond("{\"result\":true}"));

        client.createCollection("mem", 8, List.of(
                new QdrantClient.QdrantPayloadIndex("groupId", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
                new QdrantClient.QdrantPayloadIndex("occurredAt", QdrantClient.QdrantPayloadIndex.Schema.INTEGER)));

        ScriptedHttpUpstream.RecordedRequest create = upstream.requestsFor("/collections/mem").get(0);
        assertThat(create.method()).isEqualTo("PUT");
        assertThat(create.body()).contains("\"size\":8").contains("\"distance\":\"Cosine\"");
        List<ScriptedHttpUpstream.RecordedRequest> indexes =
                upstream.requestsFor("/collections/mem/index");
        assertThat(indexes).hasSize(2);
        assertThat(indexes.get(0).body()).contains("\"field_name\":\"groupId\"")
                .contains("\"field_schema\":\"keyword\"");
        assertThat(indexes.get(1).body()).contains("\"field_name\":\"occurredAt\"")
                .contains("\"field_schema\":\"integer\"");
    }

    @Test
    void describe解析维度与点数_集合不存在返回null() {
        upstream.addScript("/collections/mem", respond(
                "{\"result\":{\"status\":\"green\",\"points_count\":12,"
                        + "\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}}}}"));

        QdrantClient.QdrantCollectionInfo info = client.describeCollection("mem");

        assertThat(info.dim()).isEqualTo(1024);
        assertThat(info.pointsCount()).isEqualTo(12);
        assertThat(info.status()).isEqualTo("green");
    }

    @Test
    void describe集合不存在返回null不抛异常() {
        upstream.addScript("/collections/absent", new ScriptedHttpUpstream.Respond(
                404, "application/json", "{\"result\":null,\"status\":\"Not Found\"}"));

        assertThat(client.describeCollection("absent")).isNull();
    }

    @Test
    void upsert请求带wait与点id向量payload() {
        upstream.addScript("/collections/mem/points", respond("{\"result\":true}"));

        client.upsert("mem", List.of(new QdrantClient.QdrantPoint(42,
                new float[]{0.1f, 0.2f},
                Map.of("groupId", "g1", "occurredAt", 1000L))));

        ScriptedHttpUpstream.RecordedRequest recorded =
                upstream.requestsFor("/collections/mem/points").get(0);
        assertThat(recorded.method()).isEqualTo("PUT");
        assertThat(recorded.rawQuery()).contains("wait=true");
        assertThat(recorded.body()).contains("\"id\":42")
                .contains("\"groupId\":\"g1\"").contains("\"occurredAt\":1000");
    }

    @Test
    void search请求体带nearest向量limit与过滤条件() {
        upstream.addScript("/collections/mem/points/query", respond(
                "{\"result\":{\"points\":[{\"id\":42,\"score\":0.97,"
                        + "\"payload\":{\"groupId\":\"g1\",\"occurredAt\":1000}}]}}"));

        List<QdrantClient.QdrantHit> hits = client.search("mem", new float[]{0.1f, 0.2f},
                new QdrantClient.QdrantFilter(
                        List.of(new QdrantClient.QdrantFilter.KeywordCondition("groupId", "g1")),
                        List.of(new QdrantClient.QdrantFilter.RangeCondition("occurredAt", 500L, 2000L))),
                50);

        ScriptedHttpUpstream.RecordedRequest recorded =
                upstream.requestsFor("/collections/mem/points/query").get(0);
        assertThat(recorded.method()).isEqualTo("POST");
        String body = recorded.body();
        assertThat(body).contains("\"query\":{\"nearest\":");
        assertThat(body).contains("\"limit\":50").contains("\"with_payload\":true");
        assertThat(body).contains("\"key\":\"groupId\"").contains("\"value\":\"g1\"");
        assertThat(body).contains("\"gte\":500").contains("\"lte\":2000");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).id()).isEqualTo(42);
        assertThat(hits.get(0).score()).isCloseTo(0.97, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(hits.get(0).payload()).containsEntry("groupId", "g1").containsEntry("occurredAt", 1000L);
    }

    @Test
    void delete请求带wait与点id清单() {
        upstream.addScript("/collections/mem/points/delete", respond("{\"result\":true}"));

        client.delete("mem", List.of(42L, 43L));

        ScriptedHttpUpstream.RecordedRequest recorded =
                upstream.requestsFor("/collections/mem/points/delete").get(0);
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.rawQuery()).contains("wait=true");
        assertThat(recorded.body()).contains("\"points\":[42,43]");
    }

    @Test
    void 切换别名请求体是delete加create原子重指() {
        upstream.addScript("/collections/aliases", respond("{\"result\":true}"));

        client.switchAlias("memory_entry", "memory_entry_v2");

        ScriptedHttpUpstream.RecordedRequest recorded =
                upstream.requestsFor("/collections/aliases").get(0);
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.body()).contains("\"delete_alias\"")
                .contains("\"create_alias\"")
                .contains("\"alias_name\":\"memory_entry\"")
                .contains("\"collection_name\":\"memory_entry_v2\"");
    }

    @Test
    void 上游错误归一为unavailable可重试码() {
        upstream.addScript("/collections/mem", respond(500, "{\"error\":\"boom\"}"));

        assertThatThrownBy(() -> client.describeCollection("mem"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(QdrantErrorCodes.QDRANT_UNAVAILABLE);
    }

    @Test
    void 响应非法JSON归一为badResponse码() {
        upstream.addScript("/collections/mem", respond("{not-json"));

        assertThatThrownBy(() -> client.describeCollection("mem"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(QdrantErrorCodes.QDRANT_BAD_RESPONSE);
    }

    @Test
    void 读超时三秒归一为unavailable_检索是主链路必须短超时() {
        // 5 秒延迟剧本压过 3 秒读超时——客户端必须按超时报 unavailable 而不是干等
        upstream.addScript("/collections/mem", new ScriptedHttpUpstream.Delay(5000));

        assertThatThrownBy(() -> client.describeCollection("mem"))
                .isInstanceOf(RetryableException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(QdrantErrorCodes.QDRANT_UNAVAILABLE);
    }

    @Test
    void 配置了apiKey则每个请求都携带_未配置则不带() {
        String describeBody = "{\"result\":{\"status\":\"green\",\"points_count\":0,"
                + "\"config\":{\"params\":{\"vectors\":{\"size\":8,\"distance\":\"Cosine\"}}}}}";
        upstream.addScript("/collections/mem", respond(describeBody));

        client.describeCollection("mem");

        ScriptedHttpUpstream.RecordedRequest withKey = upstream.requestsFor("/collections/mem").get(0);
        assertThat(withKey.headers().getOrDefault("api-key", List.of())).contains("it-key-1");

        QdrantRestClient noKey = new QdrantRestClient(
                new HttpClientHolder(null), upstream.baseUrl(), "");
        upstream.addScript("/collections/mem", respond(describeBody));
        noKey.describeCollection("mem");
        ScriptedHttpUpstream.RecordedRequest withoutKey =
                upstream.requestsFor("/collections/mem").get(1);
        assertThat(withoutKey.headers()).doesNotContainKey("api-key");
    }

    private static ScriptedHttpUpstream.Respond respond(String body) {
        return new ScriptedHttpUpstream.Respond(200, "application/json", body);
    }

    private static ScriptedHttpUpstream.Respond respond(int status, String body) {
        return new ScriptedHttpUpstream.Respond(status, "application/json", body);
    }
}
