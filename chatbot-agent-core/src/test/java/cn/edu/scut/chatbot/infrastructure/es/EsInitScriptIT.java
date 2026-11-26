// 守护纪律：观测栈初始化脚本与查询口径的一致性（spec US4 验收场景 2、T039）——
// es-init 脚本真实执行后 ILM 两条策略与三份数据流模板就位；查询服务对新数据流
// 读写往返成功（模板 mapping 与服务读口径互相咬合，不是各说各话）
package cn.edu.scut.chatbot.infrastructure.es;

import cn.edu.scut.chatbot.fixtures.AbstractObservabilityIT;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * es-init 脚本 IT（T039）：真实执行 deploy/es-init/init.sh（bash + curl，测试机
 * Linux 环境；本机无 Docker 不跑 IT）——脚本幂等 PUT 两条 ILM 策略与三份数据流
 * 模板。断言后经 TraceQueryService / LogQueryService 对按模板新建的数据流做
 * 读写往返（quickstart 手工场景 2.1/2.4 的自动化前置）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EsInitScriptIT extends AbstractObservabilityIT {

    private static final String TRACE_STREAM = "traces-it2";
    private static final String AGENT_LOG_STREAM = "logs-agent-it2";

    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private TraceQueryService traceQueryService;
    @Autowired
    private LogQueryService logQueryService;

    private static Path initScript;

    @BeforeAll
    void runInitScript() throws Exception {
        initScript = locateInitScript();
        // IT 容器关闭安全认证：脚本必填校验要非空密码，给占位值（ES 忽略多余认证头）
        ProcessBuilder builder = new ProcessBuilder("bash", initScript.toString());
        builder.environment().put("ES_URL", esUrl());
        builder.environment().put("ES_USERNAME", "elastic");
        builder.environment().put("ES_PASSWORD", "it-placeholder");
        builder.redirectErrorStream(true);
        Process p = builder.start();
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(finished).as("es-init 脚本应在 60 秒内结束。输出:\n%s", output).isTrue();
        assertThat(p.exitValue()).as("es-init 脚本应成功退出。输出:\n%s", output).isZero();
    }

    @AfterAll
    void cleanObservabilityArtifacts() {
        try {
            esClient.indices().deleteDataStream(d -> d.name(TRACE_STREAM, AGENT_LOG_STREAM));
        } catch (Exception ignored) {
            // 数据流不存在即无需清理
        }
        for (String template : List.of("traces-template", "logs-agent-template", "logs-system-template")) {
            try {
                esClient.indices().deleteIndexTemplate(t -> t.name(template));
            } catch (Exception ignored) {
                // 模板不存在即无需清理
            }
        }
        for (String policy : List.of("chatbot-observe-14d", "chatbot-observe-7d")) {
            try {
                esClient.ilm().deleteLifecycle(l -> l.name(policy));
            } catch (Exception ignored) {
                // 策略不存在即无需清理
            }
        }
    }

    @Test
    void ILM策略与数据流模板就位() throws Exception {
        var policy14d = esClient.ilm().getLifecycle(
                g -> g.name("chatbot-observe-14d")).result().get("chatbot-observe-14d");
        assertThat(policy14d.policy().phases().delete().minAge().time()).isEqualTo("14d");

        var policy7d = esClient.ilm().getLifecycle(
                g -> g.name("chatbot-observe-7d")).result().get("chatbot-observe-7d");
        assertThat(policy7d.policy().phases().delete().minAge().time()).isEqualTo("7d");

        assertThat(esClient.indices().getIndexTemplate(
                t -> t.name("traces-template")).indexTemplates()).hasSize(1);
        assertThat(esClient.indices().getIndexTemplate(
                t -> t.name("logs-agent-template")).indexTemplates()).hasSize(1);
        assertThat(esClient.indices().getIndexTemplate(
                t -> t.name("logs-system-template")).indexTemplates()).hasSize(1);
    }

    @Test
    void 查询服务对新数据流读写往返() throws Exception {
        // 数据流写入按模板建流（op_type=create，数据流形态与生产 Collector 一致）
        esClient.index(i -> i.index(TRACE_STREAM).opType(co.elastic.clients.elasticsearch._types.OpType.Create)
                .document(Map.of(
                        "@timestamp", "2026-11-26T08:00:00Z",
                        "traceId", "es-init-trace-1",
                        "spanId", "span-a",
                        "name", "intake",
                        "startTimeMillis", 1000L,
                        "durationMillis", 5L,
                        "attributes", Map.of("chatbot.group_id", "88001"))));
        esClient.index(i -> i.index(AGENT_LOG_STREAM).opType(co.elastic.clients.elasticsearch._types.OpType.Create)
                .document(Map.of(
                        "@timestamp", "2026-11-26T08:00:01Z",
                        "severity", "INFO",
                        "logger", "agent.decision",
                        "message", "es-init 读写往返验证",
                        "group_id", "88001",
                        "log", Map.of("category", "agent"))));
        esClient.indices().refresh(r -> r.index(TRACE_STREAM, AGENT_LOG_STREAM));

        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                !traceQueryService.getTrace("es-init-trace-1").isEmpty());

        List<SpanView> spans = traceQueryService.getTrace("es-init-trace-1");
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).name()).isEqualTo("intake");
        assertThat(spans.get(0).attributes()).containsEntry("chatbot.group_id", "88001");

        LogPage page = logQueryService.query(
                new LogFilter(null, null, "88001", null, null, "agent", 0, 10));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).message()).isEqualTo("es-init 读写往返验证");
    }

    /**
     * 从模块工作目录向上定位 deploy/es-init/init.sh（failsafe 工作目录是模块根）
     */
    private Path locateInitScript() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("deploy").resolve("es-init").resolve("init.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("未找到 deploy/es-init/init.sh（user.dir="
                + System.getProperty("user.dir") + "）");
    }
}
