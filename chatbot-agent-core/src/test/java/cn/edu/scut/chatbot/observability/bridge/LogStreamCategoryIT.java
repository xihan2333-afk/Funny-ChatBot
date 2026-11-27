// 守护纪律：日志双流物理分离的数据面前提（设计方案 2.5.3、T024、SC US2）——
// log.category=agent / system 两条日志在 ES 侧分属两个数据流索引，按类别过滤互不串；
// 断言经 LogQueryService 正式口径（T026 落地后回填，观察查询服务的 category 语义）
package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.fixtures.AbstractObservabilityIT;
import cn.edu.scut.chatbot.infrastructure.es.LogFilter;
import cn.edu.scut.chatbot.infrastructure.es.LogPage;
import cn.edu.scut.chatbot.infrastructure.es.LogQueryService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.OpType;
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
 * log.category——LogQueryService 按此口径过滤）。断言按类别检索各自命中、互不越界，
 * 并经 LogQueryService 验证查询服务口径与数据面一致（T026 正式断言回填）。
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
    @Autowired
    private LogQueryService logQueryService;

    @BeforeAll
    void seedLogDocuments() throws Exception {
        // 生产形态（deploy/es-init 契约）：Collector 写入前先立数据流模板。ES 8 的内置
        // logs-*-* 模板是节点启动的延迟安装项——容器就绪即写会撞上安装窗口，op_type=create
        // 退化为普通索引（合跑实测）。显式立模板让数据流形态不再赌内置模板的时序。
        esClient.indices().putIndexTemplate(p -> p
                .name("logs-it-template")
                .indexPatterns(List.of(AGENT_INDEX, SYSTEM_INDEX))
                // 层级纪律：内置 logs 模板 100 < es-init 生产模板 200 < 测试模板 300——
                // 同优先级多模板匹配会让数据流创建产生歧义（合跑实测）
                .priority(300)
                .dataStream(d -> d)
                .template(t -> t
                        .mappings(m -> m
                                .properties("@timestamp", pr -> pr.date(da -> da))
                                .properties("log", pr -> pr.object(o -> o
                                        .properties("category", pc -> pc.keyword(k -> k)))))));

        // 两个数据流各写一条（log.category 由应用侧 CategoryRoutingOtlpAppender 附加，
        // Collector 只做搬运——IT 直接构造同形态文档验证数据面前提）。
        // ES 8 数据流只接受 op_type=create 且不允许显式 id（生产 Collector 的写入形态同此）
        esClient.index(i -> i.index(AGENT_INDEX)
                .opType(OpType.Create)
                .document(Map.of(
                        "log", Map.of("category", "agent"),
                        "@timestamp", 1_780_000_000_000L,
                        "severity", "INFO",
                        "logger", "agent.decision",
                        "message", "决策流水：L0 accept（模拟）",
                        "group_id", "80001")));
        esClient.index(i -> i.index(SYSTEM_INDEX)
                .opType(OpType.Create)
                .document(Map.of(
                        "log", Map.of("category", "system"),
                        "@timestamp", 1_780_000_001_000L,
                        "severity", "INFO",
                        "logger", "cn.edu.scut.chatbot.bootstrap.AgentCoreApplication",
                        "message", "系统日志：Effects 扫描启动（模拟）")));

        esClient.indices().refresh(r -> r.index(AGENT_INDEX, SYSTEM_INDEX));
    }

    @AfterAll
    void cleanIndices() throws Exception {
        // 测试机纪律：ryk 禁用下容器不自动清理——数据流显式删除不留脏数据
        esClient.indices().deleteDataStream(d -> d.name(AGENT_INDEX, SYSTEM_INDEX));
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
        // 数据流检索命中的是 backing index（.ds-logs-agent-it-<日期>-<代>）——前缀即可判归属
        assertThat(agentDocs.get(0).index()).startsWith(".ds-" + AGENT_INDEX);

        SearchResponse<JsonNode> systemHit = esClient.search(s -> s
                        .index(AGENT_INDEX, SYSTEM_INDEX)
                        .query(q -> q.term(t -> t.field("log.category").value("system"))),
                JsonNode.class);
        List<Hit<JsonNode>> systemDocs = systemHit.hits().hits();
        assertThat(systemDocs).hasSize(1);
        assertThat(systemDocs.get(0).index()).startsWith(".ds-" + SYSTEM_INDEX);
    }

    @Test
    void 经LogQueryService按category过滤各命中各自数据流() {
        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                logQueryService.query(new LogFilter(null, null, null, null, null, "agent", 0, 10))
                        .total() >= 1);

        LogPage agentPage = logQueryService.query(
                new LogFilter(null, null, null, null, null, "agent", 0, 10));
        assertThat(agentPage.total()).isEqualTo(1);
        assertThat(agentPage.items()).hasSize(1);
        assertThat(agentPage.items().get(0).category()).isEqualTo("agent");
        assertThat(agentPage.items().get(0).logger()).isEqualTo("agent.decision");
        assertThat(agentPage.items().get(0).groupId()).isEqualTo("80001");

        LogPage systemPage = logQueryService.query(
                new LogFilter(null, null, null, null, null, "system", 0, 10));
        assertThat(systemPage.total()).isEqualTo(1);
        assertThat(systemPage.items().get(0).category()).isEqualTo("system");

        // 不带 category 查两类（logs-* 通配两条数据流）
        LogPage bothPage = logQueryService.query(
                new LogFilter(null, null, null, null, null, null, 0, 10));
        assertThat(bothPage.total()).isEqualTo(2);

        // 群过滤只命中 agent 流（system 文档无 group_id）
        LogPage groupPage = logQueryService.query(
                new LogFilter(null, null, "80001", null, null, null, 0, 10));
        assertThat(groupPage.total()).isEqualTo(1);
        assertThat(groupPage.items().get(0).category()).isEqualTo("agent");
    }
}
