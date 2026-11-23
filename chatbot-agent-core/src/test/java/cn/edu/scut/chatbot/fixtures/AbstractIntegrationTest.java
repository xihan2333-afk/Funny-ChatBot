package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.bootstrap.AgentCoreApplication;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

import static org.awaitility.Awaitility.await;

/**
 * agent-core 集成测试基类（宪法第七条分层纪律）：Testcontainers 起 MySQL 8 / Redis 7 真容器
 * （禁 H2 / 内嵌 Redis 替身——uk_dedup 拒插幂等、ON DUPLICATE KEY 合并、pending 重投这些
 * 纪律藏在中间件真实行为里）；Flyway 随 Spring Boot 自动执行 V1~V4 建表与种子。
 *
 * <p>容器为单例模式（与 001 接入层基建同形态）：全部测试类共享、首个类加载时启动一次；
 * 类级 @DirtiesContext 关闭 Spring 上下文，避免常驻消费者线程跨类干扰。
 *
 * <p>异步断言一律 Awaitility（{@code await()} 已静态导入；禁 Thread.sleep——宪法测试纪律）。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AgentCoreApplication.class)
public abstract class AbstractIntegrationTest {

    /**
     * 入流键（与 application.yml 默认值一致；XADD 注入与断言的直操作面，research R9：
     * agent-core 的端到端测试不用假 NapCat，直接在流上操作即是最真实的输入面）
     */
    public static final String IN_STREAM_KEY = "chatbot:in:onebot-v11";

    /**
     * 出流键（出事件断言面）
     */
    public static final String OUT_STREAM_KEY = "chatbot:out:onebot-v11";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("funnychat")
            .withUsername("root")
            .withPassword("dev123");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .withCommand("--appendonly yes");

    static {
        MYSQL.start();
        REDIS.start();
    }

    /**
     * 集成测试可写的临时目录（固定时钟场景不需要；需要文件系统的用例通过 getTestTempDir 拿）
     */
    @TempDir
    Path testTempDir;

    /**
     * 连接配置全部指向真容器（Flyway 随 Boot 自动配置执行 classpath:db/migration）
     */
    @DynamicPropertySource
    static void middlewareProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("agent.stream.in-key", () -> IN_STREAM_KEY);
        registry.add("agent.stream.out-key", () -> OUT_STREAM_KEY);
    }

    protected Path getTestTempDir() {
        return testTempDir;
    }

    protected static MySQLContainer<?> mysql() {
        return MYSQL;
    }

    protected static GenericContainer<?> redis() {
        return REDIS;
    }
}
