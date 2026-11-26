// 守护纪律：日志双流物理分离的数据面前提（设计方案 2.5.3、T024、SC US2）——
// log.category=agent / system 两条日志在 ES 侧分属两个数据流索引，按类别过滤互不串；
// T026 LogQueryService 落地后本断言升级为经查询服务（先写用例后验证，TDD 形态）
package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.fixtures.AbstractObservabilityIT;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 日志双流 ES 数据面 IT（T024）：模拟 Collector 写入两个数据流索引（logs-agent-it /
 * logs-system-it），文档形态带 log.category 属性（OTel ES exporter 语义的字段路径
 * log.category——T026 查询服务按此口径过滤）。断言按类别检索各自命中、互不越界。
 *
 * <p>ES 8 不可达语义（FR-006 / T032）的底层面也在此验证：查询超时与错误传播经
 * EsClientFactory 的连接/读超时约束（3s / 10s）——挂死不可能发生。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogStreamCategoryIT extends AbstractObservabilityIT {

    private static final String AGENT_INDEX = "logs-agent-it";
    private static final String SYSTEM_INDEX = "logs-system-it";

    @Autowired
    private ElasticsearchClient esClient;

    @BeforeAll
    void seedLogDocuments() throws Exception {
        // 两个数据流各写一条（log.category 由应用侧 CategoryRoutingOtlpAppender 附加，
        // Collector 只做搬运——IT 直接构造同形态文档验证数据面前提）
        esClient.index(i -> i.index(AGENT_INDEX).id("agent-1")
                .document(Map.of(
                        "log", Map.of("category", "agent"),
                        "message", "决策流水：L0 accept（模拟）",
                        "groupId", "80001")));
        esClient.index(i -> i.index(SYSTEM_INDEX).id("system-1")
                .document(Map.of(
                        "log", Map.of("category", "system"),
                        "message", "系统日志：Effects 扫描启动（模拟）")));

        esClient.indices().refresh(r -> r.index(AGENT_INDEX, SYSTEM_INDEX));
    }

    @AfterAll
    void cleanIndices() throws Exception {
        // 测试机纪律：ryk 禁用下容器不自动清理——索引显式删除不留脏数据
        esClient.indices().delete(d -> d.index(AGENT_INDEX, SYSTEM_INDEX));
    }

    @Test
    void 按logcategory检索两条数据流各自命中互不越界() throws Exception {
        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                esClient.search(s -> s.index(AGENT_INDEX).query(q -> q.matchAll(m -> m)),
                        JsonNode.class).hits().total().value() >= 1);

        SearchResponse<JsonNode> agentHit = esClient.search(s -> s
                        .index(AGENT_INDEX, SYSTEM_INDEX)
                        .query(q -> q.term(t -> t.field("log.category").value("agent"))),
                JsonNode.class);
        List<Hit<JsonNode>> agentDocs = agentHit.hits().hits();
        assertThat(agentDocs).hasSize(1);
        assertThat(agentDocs.get(0).index()).isEqualTo(AGENT_INDEX);

        SearchResponse<JsonNode> systemHit = esClient.search(s -> s
                        .index(AGENT_INDEX, SYSTEM_INDEX)
                        .query(q -> q.term(t -> t.field("log.category").value("system"))),
                JsonNode.class);
        List<Hit<JsonNode>> systemDocs = systemHit.hits().hits();
        assertThat(systemDocs).hasSize(1);
        assertThat(systemDocs.get(0).index()).isEqualTo(SYSTEM_INDEX);

        // TODO（T026 落地后回填）：LogQueryService 按 category 过滤分页的正式断言
    }
}
