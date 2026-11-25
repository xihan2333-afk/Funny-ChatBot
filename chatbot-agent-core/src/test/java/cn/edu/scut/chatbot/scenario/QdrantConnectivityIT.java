// 守护纪律：集成中间件一律真容器（宪法第七条）——Qdrant 容器随 AbstractIntegrationTest
// 单例装配，本冒烟用例验证容器真的起得来、REST 面可用（建集合 + describeCollection 返回维度）
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qdrant 容器连通性冒烟（T004 验证用例）：PUT 建集合 → GET describe 断言向量维度回读。
 * 用裸 JDK HttpClient 直打 REST 面——QdrantRestClient 六端点实现前的基建先行验证；
 * 后续 IT（CollectionRebuild / MemoryRetrieval 等）经客户端走同一条链路。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QdrantConnectivityIT extends AbstractIntegrationTest {

    private static final String SMOKE_COLLECTION = "it_qdrant_smoke";

    @AfterAll
    static void cleanupSmokeCollection() throws Exception {
        request("DELETE", "/collections/" + SMOKE_COLLECTION, null);
    }

    @Test
    void 建集合后describe返回集合维度信息() throws Exception {
        HttpResponse<String> created = request("PUT", "/collections/" + SMOKE_COLLECTION,
                "{\"vectors\":{\"size\":8,\"distance\":\"Cosine\"}}");
        assertThat(created.statusCode()).isEqualTo(200);

        HttpResponse<String> described = request("GET", "/collections/" + SMOKE_COLLECTION, null);
        assertThat(described.statusCode()).isEqualTo(200);
        assertThat(described.body()).contains("\"size\":8");
    }

    private static HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(qdrantBaseUrl() + path))
                .header("Content-Type", "application/json");
        switch (method) {
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            default -> throw new IllegalArgumentException("不支持的方法：" + method);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
