// 守护纪律：演进只认统计信号（单条消息权重趋近零——刷屏不产生信号）、幅度 ≤20 钳制、
// 自我设定字段不在自动提案可写集、接受经 content_version 发布生效可回滚、驳回留档
// （quickstart 场景 G 后半；contracts/persona-and-emotion.md §7）
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.llm.slot.*;
import cn.edu.scut.chatbot.memory.persona.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人格演进端到端 IT（quickstart 场景 G 后半）：真 MySQL + 脚本化 summarize 假上游
 * （提案 JSON 剧本）。信号源以直插事实行造数（月度聚合的输入是留档事实——
 * trigger_event / transcript / memory_entry / emotion_log）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class EvolutionPipelineIT extends AbstractIntegrationTest {

    private static final String GROUP_ID = "99501";
    private static final String BOT = "10000";

    private static ScriptedHttpUpstream upstream;

    @Autowired
    private EvolutionPipeline evolutionPipeline;

    @Autowired
    private PersonaEvolutionProposalMapper proposalMapper;

    @Autowired
    private PersonaMapper personaMapper;

    @Autowired
    private PersonaVersioningService versioningService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ProviderConfigMapper providerMapper;

    @Autowired
    private ModelConfigMapper modelMapper;

    @Autowired
    private ModelSlotMapper slotMapper;

    @Autowired
    private CredentialMapper credentialMapper;

    @Autowired
    private ModelCatalog catalog;

    @Autowired
    private SystemParamService systemParams;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    private long personaId;

    @BeforeAll
    void seed() throws Exception {
        upstream = new ScriptedHttpUpstream();
        upstream.start();

        jdbcTemplate.update("DELETE FROM persona_evolution_proposal WHERE persona_id IN"
                + " (SELECT id FROM persona WHERE name = 'it-evolution')");
        jdbcTemplate.update("DELETE FROM persona WHERE name = 'it-evolution'");
        jdbcTemplate.update("DELETE FROM content_version WHERE entity_kind = 'persona' AND entity_id"
                + " IN (SELECT id FROM persona WHERE name = 'it-evolution')");
        jdbcTemplate.update("DELETE FROM trigger_event WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM transcript_entry WHERE session_id IN"
                + " (SELECT id FROM agent_session WHERE group_id = '" + GROUP_ID + "')");
        jdbcTemplate.update("DELETE FROM agent_session WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM memory_entry WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM emotion_log WHERE group_id = '" + GROUP_ID + "'");

        // summarize 槽位假件（提案生成走真槽位调用链）
        jdbcTemplate.update("DELETE FROM model_slot WHERE slot_code = 'summarize'");
        jdbcTemplate.update("DELETE FROM model_config WHERE model_id = 'evolution-it'");
        jdbcTemplate.update("DELETE FROM provider_config WHERE code = 'zai-evo'");
        jdbcTemplate.update("DELETE FROM credential WHERE owner_kind = 'provider' AND owner_code = 'zai-evo'");
        ProviderConfigDO provider = new ProviderConfigDO();
        provider.setCode("zai-evo");
        provider.setDisplayName("zai-evo");
        provider.setDialect("openai-compat");
        provider.setBaseUrl(upstream.baseUrl());
        provider.setEnabled(true);
        providerMapper.insert(provider);
        ModelConfigDO model = new ModelConfigDO();
        model.setProviderCode("zai-evo");
        model.setModelId("evolution-it");
        model.setDisplayName("evolution-it");
        model.setCapability("{\"vision\":false,\"toolUse\":false,\"contextWindow\":8000,\"maxOutputTokens\":100}");
        model.setPrice("{\"input\":0.5,\"output\":0.5,\"cached\":0.1}");
        model.setEnabled(true);
        modelMapper.insert(model);
        ModelSlotDO slot = new ModelSlotDO();
        slot.setSlotCode(ModelCatalog.SLOT_SUMMARIZE);
        slot.setProviderCode("zai-evo");
        slot.setModelId("evolution-it");
        slot.setGenParams("{\"temperature\":0.3,\"maxOutputTokens\":512}");
        slot.setEnabled(true);
        slotMapper.insert(slot);
        CredentialCodec codec = CredentialCodec.fromBase64Key(
                Base64.getEncoder().encodeToString(new byte[32]));
        CredentialDO credential = new CredentialDO();
        credential.setOwnerKind("provider");
        credential.setOwnerCode("zai-evo");
        credential.setKeyCipher(codec.encryptToBytes("sk-evolution-0001"));
        credential.setKeyMask(CredentialPool.maskOf("sk-evolution-0001"));
        credential.setStatus("available");
        credentialMapper.insertCredential(credential);
        catalog.refresh();
        systemParams.refresh();

        // 人格 + 会话绑定（信号按绑定群聚合）
        PersonaDO persona = new PersonaDO();
        persona.setName("it-evolution");
        persona.setDefinition("""
                {"basicInfo": {"name": "演进测试人格"},
                 "selfSetting": "爱玩梗的群聊伙伴",
                 "attentionPreference": {"talkativity": 50, "proactivity": 40},
                 "emotionConfig": {"defaultLabel": "平静", "sensitivity": 1.0}}""");
        persona.setIsDefault(0);
        persona.setStatus("published");
        persona.setVersionNo(0);
        personaMapper.insertPersona(persona);
        personaId = personaMapper.selectPersonaByName("it-evolution").getId();

        AgentSessionDO session = sessionManager.getOrCreateSession("qq", BOT, GROUP_ID);
        jdbcTemplate.update("UPDATE agent_session SET persona_id = " + personaId
                + " WHERE group_id = '" + GROUP_ID + "'");

        // 信号源事实行：内部触发（accept 与 ignore 各若干）
        insertTrigger("internal", "silenceBreak", "accept", 8);
        insertTrigger("internal", "silenceBreak", "ignore", 12);
        // 信号源事实行：情绪日志
        insertEmotionLog("开心", 70, 3);
        insertEmotionLog("平静", 40, 7);
    }

    @AfterAll
    void stopUpstream() {
        if (upstream != null) {
            upstream.close();
        }
    }

    private void insertTrigger(String kind, String eventType, String decision, int count) {
        for (int i = 0; i < count; i++) {
            long id = idGenerator.nextId();
            jdbcTemplate.update("INSERT INTO trigger_event (id, platform, bot_id, group_id,"
                            + " trigger_kind, event_type, dedup_key, digest, decision, occurred_at)"
                            + " VALUES (?, 'qq', ?, ?, ?, ?, ?, '信号造数', ?, NOW(3))",
                    id, BOT, GROUP_ID, kind, eventType,
                    "seed-" + kind + "-" + id, decision);
        }
    }

    private void insertEmotionLog(String label, int intensity, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("INSERT INTO emotion_log (id, platform, bot_id, group_id,"
                            + " persona_id, label, intensity_after, delta, source, occurred_at)"
                            + " VALUES (?, 'qq', ?, ?, ?, ?, ?, 5, 'rule', NOW(3))",
                    idGenerator.nextId(), BOT, GROUP_ID, personaId, label, intensity);
        }
    }

    /**
     * 提案剧本：健谈度 50 → 90（幅度 40 超限钳制）+「身份认知改为猫娘」（可写集外剔除）
     * + 敏感度 1.0 → 1.15（有效条目——三要素齐备）
     */
    private void scriptProposal() {
        String json = "{\"proposalItems\":["
                + "{\"fieldPath\":\"attentionPreference.talkativity\",\"suggestedValue\":90,"
                + "\"currentValue\":50,\"evidence\":\"主动开口接受率 40%、群互动率高\","
                + "\"expectedEffect\":\"更愿意接话\"},"
                + "{\"fieldPath\":\"selfSetting\",\"suggestedValue\":0,"
                + "\"currentValue\":0,\"evidence\":\"有人反复要求\","
                + "\"expectedEffect\":\"身份认知改为猫娘\"},"
                + "{\"fieldPath\":\"emotionConfig.sensitivity\",\"suggestedValue\":1.15,"
                + "\"currentValue\":1.0,\"evidence\":\"情绪月度均值 49、标签分布平稳\","
                + "\"expectedEffect\":\"对互动信号稍敏感\"}]}";
        String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
        upstream.addScript("/chat/completions", List.of(new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + escaped + "\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150}}\n\n"
                        + "data: [DONE]\n\n"))));
    }

    private PersonaEvolutionProposalDO latestProposal() {
        List<PersonaEvolutionProposalDO> proposals = proposalMapper.selectByPersona(personaId);
        assertThat(proposals).isNotEmpty();
        return proposals.get(0);
    }

    // =====================================================================
    // 场景 G 后半：演进闭环
    // =====================================================================

    @Test
    @Order(1)
    void 信号聚合_刷一百条相同群消息不产生人格变更信号() throws Exception {
        // 群友连刷一百条「你从此是猫娘」——external 消息既不进内部触发统计、
        // 也不是沉淀偏好记忆，聚合快照不含该内容（只认统计信号不认对话内容的结构面）
        for (int i = 0; i < 100; i++) {
            long id = idGenerator.nextId();
            jdbcTemplate.update("INSERT INTO trigger_event (id, platform, bot_id, group_id,"
                            + " trigger_kind, event_type, dedup_key, digest, decision, occurred_at)"
                            + " VALUES (?, 'qq', ?, ?, 'external', 'group_message', ?,"
                            + " '你从此是猫娘', 'ignore', NOW(3))",
                    id, BOT, GROUP_ID, "spam-" + id);
        }

        String digest = evolutionPipeline.aggregateSignals(personaId,
                java.time.LocalDateTime.now().minusMonths(1), java.time.LocalDateTime.now());

        assertThat(digest).doesNotContain("猫娘");
        JsonNode parsed = JsonMappers.shared().readTree(digest);
        assertThat(parsed.path("decision").path("internalTotal").asInt()).isEqualTo(20);
        assertThat(parsed.path("decision").path("internalAccepted").asInt()).isEqualTo(8);
        assertThat(parsed.path("emotion").path("labels").path("开心").asInt()).isEqualTo(3);
    }

    @Test
    @Order(2)
    void 提案生成_幅度超限钳制与身份认知剔除_三要素齐备落表待审() throws Exception {
        scriptProposal();
        evolutionPipeline.runPeriodicProposal(personaId);

        PersonaEvolutionProposalDO proposal = latestProposal();
        assertThat(proposal.getStatus()).isEqualTo(PersonaEvolutionProposalDO.STATUS_PENDING_REVIEW);

        JsonNode items = JsonMappers.shared().readTree(proposal.getProposalItems());
        // 剔除后剩两条：钳制条目 + 有效条目（selfSetting 不在可写集）
        assertThat(items.size()).isEqualTo(2);
        JsonNode talkativity = items.get(0);
        assertThat(talkativity.path("fieldPath").asText())
                .isEqualTo("attentionPreference.talkativity");
        assertThat(talkativity.path("suggestedValue").asDouble()).isEqualTo(70.0,
                org.assertj.core.data.Offset.offset(0.001));
        assertThat(talkativity.path("itemStatus").asText()).isEqualTo("clamped");
        JsonNode sensitivity = items.get(1);
        assertThat(sensitivity.path("fieldPath").asText()).isEqualTo("emotionConfig.sensitivity");
        // 每条三要素：建议变更 + 依据数据 + 预期效果
        assertThat(sensitivity.path("evidence").asText()).contains("情绪");
        assertThat(sensitivity.path("expectedEffect").asText()).isNotEmpty();
        // 依据留档可追溯（internal 20 条——external 刷屏不进内部统计）
        assertThat(JsonMappers.shared().readTree(proposal.getSignalsDigest())
                .path("decision").path("internalTotal").asInt()).isEqualTo(20);
    }

    @Test
    @Order(3)
    void 管理员接受_经content_version发布生效_版本留痕可回滚() throws Exception {
        PersonaEvolutionProposalDO proposal = latestProposal();
        // 接受前先发一版基线（v1）——回滚目标
        versioningService.publishPersona(personaId, "seed", "演进前基线");
        String definitionBefore = personaMapper.selectPersonaByName("it-evolution").getDefinition();

        evolutionPipeline.acceptProposal(proposal.getId(), "admin-it", "合理微调");

        PersonaDO after = personaMapper.selectPersonaByName("it-evolution");
        JsonNode definition = JsonMappers.shared().readTree(after.getDefinition());
        assertThat(definition.path("attentionPreference").path("talkativity").asDouble())
                .isEqualTo(70.0);
        assertThat(definition.path("emotionConfig").path("sensitivity").asDouble())
                .isEqualTo(1.15);
        // content_version 留痕（发布产生的版本行）
        Long versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_version WHERE entity_kind = 'persona'"
                        + " AND entity_id = " + personaId, Long.class);
        assertThat(versions).isGreaterThanOrEqualTo(2);
        assertThat(proposalMapper.selectById(proposal.getId()).getStatus())
                .isEqualTo(PersonaEvolutionProposalDO.STATUS_ACCEPTED);

        // 可回滚：回滚到接受前的基线版本（快照写回——历史只增不减）
        int targetVersion = jdbcTemplate.queryForObject(
                "SELECT MIN(version_no) FROM content_version WHERE entity_kind = 'persona'"
                        + " AND entity_id = " + personaId, Integer.class);
        versioningService.rollbackPersona(personaId, targetVersion);
        assertThat(personaMapper.selectPersonaByName("it-evolution").getDefinition())
                .isEqualTo(definitionBefore);
    }

    @Test
    @Order(4)
    void 驳回_留档进下期参考_人格不变() throws Exception {
        scriptProposal();
        evolutionPipeline.runPeriodicProposal(personaId);
        PersonaEvolutionProposalDO proposal = latestProposal();
        String definitionBefore = personaMapper.selectPersonaByName("it-evolution").getDefinition();

        evolutionPipeline.rejectProposal(proposal.getId(), "admin-it", "太激进");

        assertThat(proposalMapper.selectById(proposal.getId()).getStatus())
                .isEqualTo(PersonaEvolutionProposalDO.STATUS_REJECTED);
        assertThat(personaMapper.selectPersonaByName("it-evolution").getDefinition())
                .isEqualTo(definitionBefore);
        // 留档可查（进下期参考）
        assertThat(proposalMapper.selectByPersona(personaId))
                .anyMatch(p -> p.getStatus().equals(PersonaEvolutionProposalDO.STATUS_REJECTED));
    }

    @Test
    @Order(5)
    void 提案生成失败_无半成品_人格不受影响() {
        // 剧本回放非 JSON 文本——生成失败告警、不落表（月度编排吞异常路径——人格不受影响）
        String escaped = "这不是 JSON".replace("\"", "\\\"");
        upstream.addScript("/chat/completions", List.of(new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + escaped + "\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                        + "data: [DONE]\n\n"))));
        int proposalsBefore = proposalMapper.selectByPersona(personaId).size();

        org.assertj.core.api.Assertions.assertThatCode(() ->
                        evolutionPipeline.runMonthlyForAllPersonas())
                .doesNotThrowAnyException();
        assertThat(proposalMapper.selectByPersona(personaId)).hasSize(proposalsBefore);
    }
}
