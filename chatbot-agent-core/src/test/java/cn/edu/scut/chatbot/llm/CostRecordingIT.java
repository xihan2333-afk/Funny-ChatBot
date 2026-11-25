// 守护纪律：真表逐行断言（openai-compat 形态与 DeepSeek prompt_cache_hit_tokens 形态、
// Failed 流、无用量流）、落账异步不阻塞调用主线程（Awaitility 禁 Thread.sleep）
// （quickstart 场景 E 全量；data-model 第 10 节）
package cn.edu.scut.chatbot.llm;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.CostAwareStreamFn;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.FixedClock;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder;
import cn.edu.scut.chatbot.llm.dialect.openai.OpenAiCompatTranslator;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.gateway.StoredMediaFetcher;
import cn.edu.scut.chatbot.llm.gateway.StreamExchange;
import cn.edu.scut.chatbot.llm.gateway.TranslateContext;
import cn.edu.scut.chatbot.llm.slot.CostRecorder;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogDO;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import cn.edu.scut.chatbot.loop.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 落账事实源集成：假上游回放带用量块正常流（openai-compat 与 DeepSeek 缓存字段形态）
 * + Failed 流 + 无用量流 → llm_call_log 逐行断言；异步落账不阻塞调用主线程。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CostRecordingIT extends AbstractIntegrationTest {

    @Autowired
    private LlmCallLogMapper llmCallLogMapper;

    @Autowired
    private CostRecorder costRecorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private cn.edu.scut.chatbot.bootstrap.SystemParamMapper systemParamMapper;

    private static ScriptedHttpUpstream upstream;

    private OpenAiCompatTranslator translator;

    private ModelRef model;

    @BeforeAll
    void seed() throws java.io.IOException {
        jdbcTemplate.update("DELETE FROM llm_call_log");
        upstream = new ScriptedHttpUpstream();
        upstream.start();
        model = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "glm-4.6",
                upstream.baseUrl(), new ModelCapabilities(true, true, 128000, 4096), null, "chat_main");
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    private synchronized OpenAiCompatTranslator translator() {
        if (translator == null) {
            SystemParamService params = new SystemParamService(systemParamMapper);
            translator = new OpenAiCompatTranslator(
                    new StreamExchange(new HttpClientHolder(params)),
                    (StoredMediaFetcher) ref -> null, new FixedClock(),
                    new IdGenerator(1, new FixedClock()));
        }
        return translator;
    }

    private void streamOnce(String sseRaw, String credential) {
        upstream.addScript("/chat/completions",
                List.of(new ScriptedHttpUpstream.SseChunks(List.of(sseRaw))));
        new CostAwareStreamFn(
                (StreamFn) (request, consumer, signal) -> translator().translate(
                        new TranslateContext(request, consumer, signal)),
                costRecorder,
                new LlmCallMeta("qq", "10000", "80001", null, "turn", "it-trace", false))
                .stream(new ModelRequest(model, List.of(LlmMessage.user("你好")), "系统", List.of(),
                                GenerationParams.defaults(), credential),
                        event -> {
                        }, CancellationSignal.create());
    }

    @Test
    void 带用量正常流落账齐备() {
        long before = countRows();
        streamOnce("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"回答\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":8,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":30},\"total_tokens\":128}}\n\n"
                        + "data: [DONE]\n\n",
                "sk-it-1");

        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> countRows() > before);
        LlmCallLogDO row = latestRow();
        assertThat(row.getSlotCode()).isEqualTo("chat_main");
        assertThat(row.getProviderCode()).isEqualTo("zai");
        assertThat(row.getModelId()).isEqualTo("glm-4.6");
        assertThat(row.getInputTokens()).isEqualTo(120);
        assertThat(row.getOutputTokens()).isEqualTo(8);
        assertThat(row.getCachedTokens()).isEqualTo(30);
        assertThat(row.getStopReason()).isEqualTo("stop");
        assertThat(row.getTraceId()).isEqualTo("it-trace");
        assertThat(row.getTotalMs()).isNotNull();
    }

    @Test
    void deepseek缓存字段形态同样归一落账() {
        long before = countRows();
        streamOnce("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"答\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":20,"
                        + "\"prompt_cache_hit_tokens\":800,\"total_tokens\":1020}}\n\n"
                        + "data: [DONE]\n\n",
                "sk-it-2");

        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> countRows() > before);
        assertThat(latestRow().getCachedTokens()).isEqualTo(800);
    }

    @Test
    void failed流照记_errorCode携带() {
        long before = countRows();
        upstream.addScript("/chat/completions", List.of(new ScriptedHttpUpstream.Respond(429,
                "application/json", "{\"error\":{\"message\":\"Rate limit\"}}")));
        new CostAwareStreamFn(
                (StreamFn) (request, consumer, signal) -> translator().translate(
                        new TranslateContext(request, consumer, signal)),
                costRecorder, LlmCallMeta.of("turn", null))
                .stream(new ModelRequest(model, List.of(LlmMessage.user("你好")), "系统", List.of(),
                                GenerationParams.defaults(), "sk-it-4"),
                        event -> {
                        }, CancellationSignal.create());

        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> countRows() > before);
        LlmCallLogDO failedRow = latestRow();
        assertThat(failedRow.getErrorCode()).isEqualTo("llm.rate_limited");
        assertThat(failedRow.getStopReason()).isEqualTo("error");
        assertThat(failedRow.getInputTokens()).isNull();
    }

    @Test
    void 无用量流落账列null不阻塞() {
        long before = countRows();
        streamOnce("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"答\"}}]}\n\n"
                + "data: [DONE]\n\n", "sk-it-5");

        await().atMost(java.time.Duration.ofSeconds(3)).until(() -> countRows() > before);
        LlmCallLogDO row = latestRow();
        assertThat(row.getInputTokens()).isNull();
        assertThat(row.getCostAmount()).isNull();   // 单价表无该模型——单价缺失同样落 null
    }

    private long countRows() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_call_log", Long.class);
        return count == null ? 0 : count;
    }

    private LlmCallLogDO latestRow() {
        return llmCallLogMapper.selectLatest(1).get(0);
    }
}
