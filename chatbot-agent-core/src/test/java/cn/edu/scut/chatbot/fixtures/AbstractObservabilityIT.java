package cn.edu.scut.chatbot.fixtures;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 观测域集成测试基类（宪法第七条分层纪律：中间件一律真容器）：在
 * {@link AbstractIntegrationTest} 的 MySQL / Redis / Qdrant 之上加 Elasticsearch 8.11.4
 * 真容器——链路 / 日志 / 指标查询与 es-init 脚本的验收藏在 ES 真实的数据流与 ILM 行为里，
 * 内存替身验证不了。
 *
 * <p>容器为单例模式：全部观测 IT 共享、首个子类加载时启动一次（对齐既有基类形态）。
 *
 * <p>测试机纪律（与 004 基线一致，quickstart 第 0 节）：
 * <ul>
 *   <li>elasticsearch:8.11.4 镜像被第三方封锁，先经 docker.1ms.run 前缀中转拉取再
 *       retag 为本地 elasticsearch:8.11.4；</li>
 *   <li>Docker 29 环境：docker-java.properties 须含 api.version=1.44（~/.docker/ 路径），
 *       且 TESTCONTAINERS_RYUK_DISABLED=true 跳过 ryuk（ryuk 对 API 1.44 协商失败）；</li>
 *   <li>ryk 禁用意味着容器不自动清理——IT 跑完手动 docker 清理（quickstart 第 0 节）。</li>
 * </ul>
 *
 * <p>ES 8 默认开启安全认证，IT 场景显式关闭（xpack.security.enabled=false）——
 * IT 验证查询语义不验证 TLS；生产形态的凭证由 EsClientFactory 的部署层配置承担。
 */
public abstract class AbstractObservabilityIT extends AbstractIntegrationTest {

    /**
     * Elasticsearch 8.11.4 单节点（与 elasticsearch-java 客户端 8.11.4 小版本对齐；
     * 测试堆 512m——IT 数据量为构造小样本，够用且不挤占测试机内存）
     */
    private static final ElasticsearchContainer ES = new ElasticsearchContainer(
            DockerImageName.parse("elasticsearch:8.11.4"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        ES.start();
    }

    /**
     * 查询链路指向真容器（observe.es-url 由 EsClientFactory 消费；凭证空——安全已关）
     */
    @DynamicPropertySource
    static void esProperties(DynamicPropertyRegistry registry) {
        registry.add("observe.es-url", AbstractObservabilityIT::esUrl);
        registry.add("observe.es-username", () -> "");
        registry.add("observe.es-password", () -> "");
        // IT 不起 Collector：OTLP 导出关（事件桥单测用内存 SpanExporter 断言——R19 IT 边界）
        registry.add("observe.otlp-enabled", () -> "false");
    }

    protected static ElasticsearchContainer es() {
        return ES;
    }

    /**
     * ES REST 根地址（容器随机端口映射后的直连形态）
     */
    protected static String esUrl() {
        return "http://" + ES.getHttpHostAddress();
    }
}
