// 守护纪律：SC-001 全程一个 traceId（spec US1 验收场景 1~6、observability-bridge 契约第 5 节）——
// 信封携带 traceContext → 主服务消费续接同 traceId；出事件信封携带新 traceparent；
// llm.call Span 用量属性与 llm_call_log 落账逐项一致（SC-002）；Collector 不可达时业务零异常
package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.outbox.OutboxDO;
import cn.edu.scut.chatbot.engine.outbox.OutboxMapper;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.EnvelopeFactory;
import cn.edu.scut.chatbot.fixtures.RedisStreamTestKit;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.llm.slot.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 跨进程接续全链路 IT（T019）：接入层语义的信封（带 traceContext）写入入流 →
 * 主服务消费 → 事件桥 Span 与信封同 traceId；出事件 outbox 行携带同 traceId 的
 * traceparent（回执结算 Span 挂回原 trace）；llm.call Span 用量与 llm_call_log
 * 落账逐项一致。OTLP 导出缺席（无 Collector）——业务全程零异常（FR-026）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class TraceContinuityObservabilityIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "94001";

    /**
     * 模拟接入层写入信封的 W3C traceparent（traceId 全零不合法，取标准示例值）
     */
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    private static final byte[] TEST_KEY = new byte[32];

    private static ScriptedHttpUpstream upstream;

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private TriggerEventMapper triggerEventMapper;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private ProviderConfigMapper providerMapper;

    @Autowired
    private ModelConfigMapper modelMapper;

    @Autowired
    private ModelSlotMapper slotMapper;

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private LlmCallLogMapper llmCallLogMapper;

    @Autowired
    private ModelCatalog catalog;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seed() throws java.io.IOException {
        upstream = new ScriptedHttpUpstream();
        upstream.start();

        jdbcTemplate.update("DELETE FROM model_slot");
        jdbcTemplate.update("DELETE FROM model_config");
        jdbcTemplate.update("DELETE FROM provider_config");
        jdbcTemplate.update("DELETE FROM credential WHERE owner_kind = 'provider'");
        jdbcTemplate.update("DELETE FROM llm_call_log");

        ProviderConfigDO provider = new ProviderConfigDO();
        provider.setCode("zai");
        provider.setDisplayName("zai");
        provider.setDialect("openai-compat");
        provider.setBaseUrl(upstream.baseUrl());
        provider.setEnabled(true);
        providerMapper.insert(provider);

        ModelConfigDO model = new ModelConfigDO();
        model.setProviderCode("zai");
        model.setModelId("glm-4.6");
        model.setDisplayName("glm-4.6");
        model.setCapability("{\"vision\":true,\"toolUse\":true,\"contextWindow\":128000,\"maxOutputTokens\":4096}");
        model.setPrice("{\"input\":1.0,\"output\":2.0,\"cached\":0.5}");
        model.setEnabled(true);
        modelMapper.insert(model);

        ModelSlotDO slot = new ModelSlotDO();
        slot.setSlotCode("chat_main");
        slot.setProviderCode("zai");
        slot.setModelId("glm-4.6");
        slot.setGenParams("{\"temperature\":0.7,\"maxOutputTokens\":2048}");
        slot.setEnabled(true);
        slotMapper.insert(slot);

        CredentialCodec codec = CredentialCodec.fromBase64Key(
                Base64.getEncoder().encodeToString(TEST_KEY));
        CredentialDO credential = new CredentialDO();
        credential.setOwnerKind("provider");
        credential.setOwnerCode("zai");
        credential.setKeyCipher(codec.encryptToBytes("sk-e2e-key-trace-0001"));
        credential.setKeyMask(cn.edu.scut.chatbot.llm.slot.CredentialPool.maskOf("sk-e2e-key-trace-0001"));
        credential.setStatus("available");
        credentialMapper.insertCredential(credential);

        catalog.refresh();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    @Test
    void 信封带traceContext全程同一traceId_出向outbox同trace_llmCall属性与落账一致() {
        SPANS.clear();
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);

        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);
        kit.clearStream(IN_STREAM_KEY);
        kit.clearStream(OUT_STREAM_KEY);

        // 假上游两轮剧本：第一轮 sendMessage 工具调用、第二轮自然结束（带 usage 供一致率断言）
        upstream.addScript("/chat/completions", List.of(new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"sendMessage\",\"arguments\":\"{\\\"te\"}}]}}]}}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"xt\\\":\\\"链路贯通测试\\\"}\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":90,\"completion_tokens\":12,\"total_tokens\":102}}\n\n",
                "data: [DONE]\n\n"))));
        upstream.addScript("/chat/completions", List.of(new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"链路测试完成\"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":5,\"total_tokens\":125}}\n\n",
                "data: [DONE]\n\n"))));

        // 1. 入流注入带 traceContext 的 @bot 消息（接入层语义——SC-001 起点）
        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.withTraceContext(
                EnvelopeFactory.groupMessage("qq", "10000", TEST_GROUP, "10086", "@bot 链路测试", null, true),
                TRACEPARENT));

        // 2. Turn 推进到可结算态（出事件已产生——回执回流前停在此，模板同构）
        await().atMost(java.time.Duration.ofSeconds(30)).until(() ->
                jdbcTemplate.queryForList("SELECT status FROM turn WHERE group_id = ?", String.class, TEST_GROUP)
                        .stream().anyMatch(status -> "SETTLING".equals(status)
                                || "SETTLED".equals(status) || "SILENT".equals(status)));
        Long turnId = jdbcTemplate.queryForObject(
                "SELECT id FROM turn WHERE group_id = ? ORDER BY id DESC LIMIT 1", Long.class, TEST_GROUP);
        List<OutboxDO> outs = outboxMapper.selectByTurn(turnId);
        assertThat(outs).isNotEmpty();
        OutboxDO outbox = outs.get(0);

        // 3. 触发器留痕带原 traceparent（trigger_event.trace_context 持久化）
        List<TriggerEventDO> triggers = triggerEventMapper.selectAllByGroup("qq", "10000", TEST_GROUP);
        assertThat(triggers).isNotEmpty();
        assertThat(triggers.get(0).getTraceContext()).isEqualTo(TRACEPARENT);

        // 4. 出事件信封携带同 traceId 的 traceparent（T015 出向接续——outbox 行回读）
        assertThat(outbox.getTraceContext()).isNotNull();
        assertThat(outbox.getTraceContext()).startsWith("00-" + TRACE_ID + "-");

        // 5. 事件桥 Span 树全程同一 traceId（内存 SpanExporter 收集，R19）
        // turn Span 例外：契约上闭合于终态迁移（SETTLED/SILENT/FAILED/DROPPED），
        // 内存导出器只见已闭合 Span——其断言移到第 6 步回执结算之后
        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                SPANS.firstByName("llm.call") != null);
        for (String name : List.of("intake", "gate.l0", "run", "llm.call", "tool.sendMessage")) {
            SpanData span = SPANS.firstByName(name);
            assertThat(span).as("Span %s 应已产生", name).isNotNull();
            assertThat(span.getTraceId()).as("Span %s 的 traceId 应与信封一致", name)
                    .isEqualTo(TRACE_ID);
        }

        // 6. 回执结算：delivery.settle Span 挂回原出事件 trace（回执引用）
        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.deliveryReportSettled(
                String.valueOf(outbox.getId()), "platform-msg-trace-1"));
        await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                SPANS.firstByName("delivery.settle") != null);
        assertThat(SPANS.firstByName("delivery.settle").getTraceId()).isEqualTo(TRACE_ID);

        // 6b. 回执结算把 turn 推进终态、turn Span 闭合导出——补验其 traceId 同源
        await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                SPANS.firstByName("turn") != null);
        assertThat(SPANS.firstByName("turn").getTraceId()).isEqualTo(TRACE_ID);

        // 7. SC-002 用量一致率：llm.call Span 用量属性与 llm_call_log 落账逐项一致
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                llmCallLogMapper.selectLatest(10).size() >= 2);
        List<SpanData> llmCalls = SPANS.byName("llm.call");
        for (LlmCallLogDO row : llmCallLogMapper.selectLatest(10)) {
            if (row.getRunKind() == null || !"turn".equals(row.getRunKind())) {
                continue;
            }
            SpanData matching = llmCalls.stream()
                    .filter(span -> span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))
                            == row.getInputTokens().longValue())
                    .findFirst()
                    .orElse(null);
            assertThat(matching).as("llm_call_log 行应有同 input 的 llm.call Span（input=%s）",
                    row.getInputTokens()).isNotNull();
            assertThat(matching.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")))
                    .isEqualTo(row.getOutputTokens().longValue());
            assertThat(matching.getAttributes().get(AttributeKey.stringKey("chatbot.slot")))
                    .isEqualTo(row.getSlotCode());
        }

        // 8. 版本三键挂 run Span（重放版本对照的口径来源）
        SpanData run = SPANS.firstByName("run");
        assertThat(run.getAttributes().get(AttributeKey.longKey("chatbot.version.promptFragment")))
                .isGreaterThanOrEqualTo(1L);
        assertThat(run.getAttributes().get(AttributeKey.longKey("chatbot.version.slotBinding")))
                .isGreaterThan(0L);
        assertThat(run.getAttributes().get(AttributeKey.stringKey("chatbot.group.group_id")))
                .isEqualTo(TEST_GROUP);
    }
}
