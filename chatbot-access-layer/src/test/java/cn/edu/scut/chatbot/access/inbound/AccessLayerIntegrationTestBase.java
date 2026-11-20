package cn.edu.scut.chatbot.access.inbound;

import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator;
import cn.edu.scut.chatbot.access.fixtures.FakeNapCat;
import cn.edu.scut.chatbot.access.fixtures.SteadyClock;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.time.Clock;

/**
 * 接入层集成测试基类（宪法第七条分层纪律）：Testcontainers 起 MySQL 8 / Redis 7 真容器
 * （禁 H2 / 内嵌 Redis 替身——幂等、pending 重投这些纪律藏在中间件真实行为里），
 * 拉起 FakeNapCat 假协议端、装配固定可拨时钟与可预测发号器，注入接入层全部连接配置。
 *
 * <p>容器为单例模式（设计方案 4.1.2「容器池」）：全部测试类共享、类级生命周期，
 * 首个测试类加载时启动一次。FakeNapCat 的令牌与接入层配置保持一致。
 *
 * <p>类级 {@code @DirtiesContext}：每个 IT 类结束后关闭 Spring 上下文。上下文缓存会让
 * 前序类的 OneBotWsClient 一直持着与假协议端的连接（带不同 @DynamicPropertySource 的类
 * 会缓存出第二个上下文 → 双客户端同时在线），FakeNapCat 的连接计数类断言随即失真。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AccessLayerIntegrationTestBase {

    public static final String NAPCAT_ACCESS_TOKEN = "integration-test-token";

    /**
     * 入流键（与 application.yml 默认值一致；断言用常量直白引用）
     */
    public static final String IN_STREAM_KEY = "chatbot:in:onebot-v11";

    /**
     * 测试时钟起点：2026-05-01T08:00:00（Asia/Shanghai），可拨动
     */
    public static final SteadyClock TEST_CLOCK = new SteadyClock(1775001600000L);

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("funnychat")
            .withUsername("root")
            .withPassword("dev123");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .withCommand("--appendonly yes");

    private static final FakeNapCat FAKE_NAPCAT = new FakeNapCat(NAPCAT_ACCESS_TOKEN);

    static {
        // 模拟生产挂 otel javaagent 的形态（R6 决策：SDK 由 agent 安装）：测试 JVM 无 agent，
        // 全局注册内存版 SDK——EventTranslator 显式开 Span 的路径产生有效 traceparent，
        // TraceContinuityIT 的 traceId 贯穿断言依赖它；未注册时该路径是 no-op、信封 traceContext 为空。
        // GlobalOpenTelemetry 的全局单例「先 get 后 set 即拒绝」，注册必须发生在任何 get 之前——
        // 基类静态块是本 JVM 最早时机；若仍被先到者锁定（异常），沿用现状，不让注册冲突拖死测试基建。
        try {
            GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().build());
        } catch (IllegalStateException globalAlreadyLocked) {
            // 全局单例已被锁定（先于本注册有人 get / set 过）：沿用现有全局实例
        }
        MYSQL.start();
        REDIS.start();
        executeRawEventLogDdl();
        try {
            FAKE_NAPCAT.start();
        } catch (Exception e) {
            throw new IllegalStateException("假 NapCat 启动失败", e);
        }
    }

    /**
     * 接入层可调用的假协议端
     */
    protected static FakeNapCat fakeNapCat() {
        return FAKE_NAPCAT;
    }

    /**
     * 协议端地址覆盖口：默认 null（连基类共享的假协议端）；需要专线剧本的测试类
     * （连接拒绝、连上即断等不共享假件的场景）在自己的静态块里赋值，例如
     * {@code napcatWsUrlOverride = "ws://127.0.0.1:" + PORT + "/";}。
     *
     * <p>不走 @DynamicPropertySource 覆盖的原因（踩坑结论）：Spring 对同 key 的动态属性是
     * 「先注册者赢」——基类的 middlewareProperties 先注册 access.napcat.ws-url，
     * 子类再注册同 key 会被静默忽略，专线剧本根本不生效（接入层仍连共享假件）。
     * 本字段在注册 lambda 里惰性读取，子类静态块（先于 Spring 上下文刷新）赋值即生效。
     * 地址建议写 127.0.0.1 直连，不走 localhost 的 IPv6 优先解析，避免引入双栈回退变数。
     */
    protected static String napcatWsUrlOverride;

    /**
     * 暂停 Redis 容器（模拟 Redis 停机——「落流成功才应答」场景的观察窗口）
     */
    protected static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    /**
     * 恢复 Redis 容器
     */
    protected static void resumeRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    @AfterAll
    static void stopFakeNapCatHeartbeat() {
        FAKE_NAPCAT.stopHeartbeat();
        // 专线剧本覆盖口复位：静态字段在同 JVM 里跨测试类共享（failsafe 单 fork 顺序执行），
        // 用过的类不清零会让后续类继续连专线死地址——共享假件的连接计数断言随之全部失真
        // （实测踩坑：覆盖口残留导致 TransportReliabilityIT 等三类 20 秒超时全灭）
        napcatWsUrlOverride = null;
    }

    /**
     * 连接配置全部指向真容器与假协议端（值来自容器随机端口）
     */
    @DynamicPropertySource
    static void middlewareProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("access.napcat.ws-url", () -> napcatWsUrlOverride != null
                ? napcatWsUrlOverride : FAKE_NAPCAT.wsUrl().toString());
        registry.add("access.napcat.access-token", () -> NAPCAT_ACCESS_TOKEN);
        registry.add("access.napcat.expected-bot-id", () -> "10000");
    }

    /**
     * 固定时钟与可预测发号器（测试基建假件，宪法第七条「固定时钟、假发号器列入测试基建」）：
     * 时间戳可精确断言，eventId 在固定时钟下序列递增、值可预期。
     */
    @TestConfiguration
    static class DeterministicTimeAndIdConfiguration {

        @Bean
        @Primary
        Clock integrationTestClock() {
            return TEST_CLOCK;
        }

        @Bean
        @Primary
        SnowflakeIdGenerator integrationTestIdGenerator() {
            // 固定时钟下的真发号器即假发号器：机器号 2、同毫秒内序列 0,1,2…递增，ID 值可预测
            return new SnowflakeIdGenerator(2, TEST_CLOCK);
        }
    }

    /**
     * 在 MySQL 容器上执行 raw_event_log 建表脚本（V1 DDL，agent-core Flyway 脚本的测试资源形态）
     */
    private static void executeRawEventLogDdl() {
        try (Connection connection = MYSQL.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/V1__raw_event_log.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("执行 V1 建表脚本失败", e);
        }
    }
}
