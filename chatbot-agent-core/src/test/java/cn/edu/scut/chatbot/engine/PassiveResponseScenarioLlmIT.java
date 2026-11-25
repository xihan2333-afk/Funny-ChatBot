// 守护纪律：StreamFn 真实现替换对循环内核零感知——002 被动回应闭环行为不变
// （@bot → L0 accept → 生成 → sendMessage → 出流 → 回执 → SETTLED）、转录含 assistant、
// llm_call_log 有记账（quickstart 场景 G）
package cn.edu.scut.chatbot.engine;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.outbox.OutboxDO;
import cn.edu.scut.chatbot.engine.outbox.OutboxMapper;
import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.EnvelopeFactory;
import cn.edu.scut.chatbot.fixtures.RedisStreamTestKit;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.llm.slot.*;
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
 * 场景 G（quickstart）：002 被动回应闭环剧本在 llm 层真实现（GatewayStreamFn + 真翻译器
 * + 假上游 SSE）下的端到端回归——全链路行为不变、llm_call_log 有记账（run_kind=turn）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class PassiveResponseScenarioLlmIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "93001";

    /**
     * 测试主密钥（base64 的 32 个零字节——与 @TestPropertySource 注入值一致）
     */
    private static final byte[] TEST_KEY = new byte[32];

    private static ScriptedHttpUpstream upstream;

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private TriggerEventMapper triggerEventMapper;

    @Autowired
    private TurnMapper turnMapper;

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

        providerMapper.insert(providerRow("zai", upstream.baseUrl()));
        modelMapper.insert(modelRow("zai", "glm-4.6"));
        slotMapper.insert(slotRow("chat_main", "zai", "glm-4.6"));

        CredentialCodec codec = CredentialCodec.fromBase64Key(
                Base64.getEncoder().encodeToString(TEST_KEY));
        CredentialDO credential = new CredentialDO();
        credential.setOwnerKind("provider");
        credential.setOwnerCode("zai");
        credential.setKeyCipher(codec.encryptToBytes("sk-e2e-key-0001"));
        credential.setKeyMask(CredentialPool.maskOf("sk-e2e-key-0001"));
        credential.setStatus("available");
        credentialMapper.insertCredential(credential);

        catalog.refresh();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    @Test
    void 场景G_被动回应闭环在llm真实现下全链路行为不变() {
        groupMapper.upsertDiscovered("qq", "10000", TEST_GROUP, null);
        groupMapper.updateInService("qq", "10000", TEST_GROUP, 1);
        groupMapper.updateLifecycle("qq", "10000", TEST_GROUP, GroupDO.LIFECYCLE_ACTIVE);
        // 清流用 XTRIM 0 只清消息保消费组（DEL 会连组一起删，消费者组只在启动时建一次）
        RedisStreamTestKit kit = new RedisStreamTestKit(redisTemplate);
        kit.clearStream(IN_STREAM_KEY);
        kit.clearStream(OUT_STREAM_KEY);

        // 假上游两轮剧本：第一轮 toolCall(sendMessage)、第二轮自然结束（与 002 场景 A 同构）
        upstream.addScript("/chat/completions", List.of(new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"sendMessage\",\"arguments\":\"{\\\"te\"}}]}}]}}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"xt\\\":\\\"收到！马上安排~\\\"}\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":90,\"completion_tokens\":12,\"total_tokens\":102}}\n\n",
                "data: [DONE]\n\n"))));
        upstream.addScript("/chat/completions", List.of(new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"已经回复啦\"}}]}\n\n",
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":5,\"total_tokens\":125}}\n\n",
                "data: [DONE]\n\n"))));

        // 1. 入流注入 @bot 消息
        kit.addToInbound(IN_STREAM_KEY,
                EnvelopeFactory.groupMessage("qq", "10000", TEST_GROUP, "10086", "帮忙看看这个", null, true));

        // 2. Turn 推进到可结算态（第一轮 sendMessage 出事件、第二轮自然结束后置
        // SETTLING——回执回流前停在此；终态 SETTLED 由第 6 步回执驱动。SETTLING 属
        // selectNonTerminalAll 的非终态口径，不能等"非终态清空"）
        await().atMost(java.time.Duration.ofSeconds(30)).until(() ->
                turnMapper.selectNonTerminalAll().stream().anyMatch(t ->
                        TEST_GROUP.equals(t.getGroupId())
                                && ("SETTLING".equals(t.getStatus())
                                || "SETTLED".equals(t.getStatus())
                                || "SILENT".equals(t.getStatus()))));
        TurnDO turn = jdbcTemplate.query(
                "SELECT id, status FROM turn WHERE group_id = ? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> {
                    TurnDO found = new TurnDO();
                    found.setId(rs.getLong("id"));
                    found.setStatus(rs.getString("status"));
                    return found;
                }, TEST_GROUP).get(0);
        assertThat(turn.getStatus()).isIn("SETTLING", "SETTLED", "SILENT");

        // 3. 触发器留痕（L0 accept）
        List<TriggerEventDO> triggers = triggerEventMapper.selectAllByGroup("qq", "10000", TEST_GROUP);
        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).getDecision()).isEqualTo("accept");

        // 4. 转录含 assistant 条目（真翻译器产出进转录）
        Integer assistantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transcript_entry WHERE kind = 'assistant'", Integer.class);
        assertThat(assistantCount).isGreaterThanOrEqualTo(1);

        // 5. llm_call_log 有记账（run_kind=turn、模型来自槽位解析）
        await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                !llmCallLogMapper.selectLatest(10).isEmpty());
        List<LlmCallLogDO> logs = llmCallLogMapper.selectLatest(10);
        assertThat(logs).anySatisfy(row -> {
            assertThat(row.getRunKind()).isEqualTo("turn");
            assertThat(row.getSlotCode()).isEqualTo("chat_main");
            assertThat(row.getModelId()).isEqualTo("glm-4.6");
            assertThat(row.getProviderCode()).isEqualTo("zai");
        });

        // 6. 出事件与回执结算（闭环证据）
        List<OutboxDO> outs = outboxMapper.selectByTurn(turn.getId());
        assertThat(outs).isNotEmpty();
        await().atMost(java.time.Duration.ofSeconds(15)).until(() ->
                !kit.readOutboundAll(OUT_STREAM_KEY).isEmpty());
        kit.addToInbound(IN_STREAM_KEY, EnvelopeFactory.deliveryReportSettled(
                String.valueOf(outs.get(0).getId()), "platform-msg-g1"));
        await().atMost(java.time.Duration.ofSeconds(15)).until(() -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM turn WHERE id = " + turn.getId(), String.class);
            return "SETTLED".equals(status);
        });
    }

    private ProviderConfigDO providerRow(String code, String baseUrl) {
        ProviderConfigDO row = new ProviderConfigDO();
        row.setCode(code);
        row.setDisplayName(code);
        row.setDialect("openai-compat");
        row.setBaseUrl(baseUrl);
        row.setEnabled(true);
        return row;
    }

    private ModelConfigDO modelRow(String providerCode, String modelId) {
        ModelConfigDO row = new ModelConfigDO();
        row.setProviderCode(providerCode);
        row.setModelId(modelId);
        row.setDisplayName(modelId);
        row.setCapability("{\"vision\":true,\"toolUse\":true,\"contextWindow\":128000,\"maxOutputTokens\":4096}");
        row.setPrice("{\"input\":1.0,\"output\":2.0,\"cached\":0.5}");
        row.setEnabled(true);
        return row;
    }

    private ModelSlotDO slotRow(String slotCode, String providerCode, String modelId) {
        ModelSlotDO row = new ModelSlotDO();
        row.setSlotCode(slotCode);
        row.setProviderCode(providerCode);
        row.setModelId(modelId);
        row.setGenParams("{\"temperature\":0.7,\"maxOutputTokens\":2048}");
        row.setEnabled(true);
        return row;
    }
}
