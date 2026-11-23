// 守护纪律：工具是模型的手指——装配即边界；上游内容以数据身份定界进入上下文（宪法第六条）
// spec US5 场景 C（quickstart 第 4 节）
package cn.edu.scut.chatbot.tool;

import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.HttpToolConfigDO;
import cn.edu.scut.chatbot.engine.intake.HttpToolConfigMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.EnvelopeFactory;
import cn.edu.scut.chatbot.fixtures.RedisStreamTestKit;
import cn.edu.scut.chatbot.fixtures.ScriptedStreamFn;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 场景 C：泛化工具配置即生效（插行 + 刷新事件 → 下一个 Run 可用）、
 * 模型 toolCall → toolResult 配对按调用顺序、上游响应定界说明。
 * SSRF 负向（内网直连、二跳内网）由 SsrfProtectionTest 离线覆盖（本机无公网出口可控环境）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ToolchainIT.ScriptedModelConfiguration.class)
class ToolchainIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "92001";

    /**
     * 脚本化 HTTP 上游（复用 001 模式：固定 JSON 响应 + 供超大响应用例的端点）
     */
    private static final HttpServer UPSTREAM;

    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/api/weather", exchange -> {
                byte[] body = "{\"data\":{\"city\":\"广州\",\"temp\":\"26C\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            UPSTREAM.start();
        } catch (Exception e) {
            throw new IllegalStateException("脚本化上游启动失败", e);
        }
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private HttpToolConfigMapper httpToolConfigMapper;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private ScriptedStreamFn scriptedStreamFn;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @TestConfiguration
    static class ScriptedModelConfiguration {

        @Bean
        @Primary
        ScriptedStreamFn scriptedStreamFn() {
            return new ScriptedStreamFn();
        }
    }

    @Test
    void 泛化工具配置即生效_调用链toolCall到toolResult配对() throws Exception {
        prepareGroup();

        // 1. 插一份泛化工具配置（指向本机脚本化上游）+ 刷新注册表
        HttpToolConfigDO config = new HttpToolConfigDO();
        config.setToolName("queryWeather");
        config.setDescription("查询指定城市的天气");
        config.setParameters("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}");
        config.setMethod("GET");
        // 注意：上游是 http://127.0.0.1 —— SSRF 防线会拦截回环地址！
        // 泛化工具执行器按纪律拒绝内网目标。本用例改考拦截路径（配置生效 + 调用被 SSRF 拒绝）
        config.setUrlTemplate("https://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/api/weather?city={{city}}");
        // 两列 NOT NULL：INSERT 显式传 null 会压过表默认值，按默认值口径显式给值
        config.setMaxResponseBytes(4096);
        config.setTimeoutMillis(3000);
        config.setEnabled(1);
        httpToolConfigMapper.deleteByName("queryWeather");
        httpToolConfigMapper.insertConfig(config);
        toolRegistry.refresh("测试插行");

        assertThat(toolRegistry.snapshot()).containsKey("queryWeather");

        // 2. 剧本模型调泛化工具（SSRF 拦截 → isError 结果）再 sendMessage
        scriptedStreamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "queryWeather",
                "{\"city\":\"广州\"}"));
        scriptedStreamFn.addScript(ScriptedStreamFn.naturalText("查询被拦截，但我还是打个招呼"));
        redisTemplate.opsForStream().trim(IN_STREAM_KEY, 0);
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);
        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.groupMessage(
                "qq", "10000", TEST_GROUP, "10086", "查下广州天气", null, true));

        // 3. 转录出现 toolResult（isError=SSRF 拦截）与后续 assistant——配对按调用顺序
        await().atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            AgentSessionDO session = sessionMapper.selectByGroup("qq", "10000", TEST_GROUP);
            if (session == null) {
                return false;
            }
            List<cn.edu.scut.chatbot.engine.session.TranscriptEntryDO> entries =
                    sessionMapper.selectOrdered(session.getId());
            return entries.stream().anyMatch(entry -> "toolResult".equals(entry.getKind())
                    && entry.getPayload().contains("queryWeather"));
        });

        AgentSessionDO session = sessionMapper.selectByGroup("qq", "10000", TEST_GROUP);
        List<cn.edu.scut.chatbot.engine.session.TranscriptEntryDO> entries =
                sessionMapper.selectOrdered(session.getId());
        // toolCall（assistant 载荷带 queryWeather 段）在前、toolResult 在后（调用顺序）
        int assistantIndex = -1;
        int toolResultIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            String payload = entries.get(i).getPayload();
            if ("assistant".equals(entries.get(i).getKind()) && payload.contains("queryWeather")) {
                assistantIndex = i;
            }
            if ("toolResult".equals(entries.get(i).getKind()) && payload.contains("queryWeather")) {
                toolResultIndex = i;
            }
        }
        assertThat(assistantIndex).isGreaterThanOrEqualTo(0);
        assertThat(toolResultIndex).isGreaterThan(assistantIndex);
        // SSRF 拦截进 isError 结果（工具失败是业务正常分支——环境隔离优于内容审查）
        assertThat(entries.get(toolResultIndex).getPayload()).contains("isError");
    }

    private void prepareGroup() {
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);
    }
}
