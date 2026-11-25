// 守护纪律：换人格不丢记忆（切换结构性不触碰记忆画像群状态）、切换历史经 content_version
// 留痕可审计（发布即快照、回滚=旧快照写回再发布）、权重与注意力偏好数值不进 systemPrompt
// （负向断言）、语义雷点素材进身份层、多群共享同一人格各自记忆按群隔离
// （quickstart 场景 G 前半；contracts/persona-and-emotion.md §1/§2/§4）
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.memory.persona.PersonaVersioningService;
import cn.edu.scut.chatbot.prompt.PromptAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人格切换与版本留痕端到端 IT（quickstart 场景 G 前半）：真 MySQL。
 * 注意力参数经人格偏好摘要进 L1 的断言依赖 T026（US6 后回补）。
 * persona 单表无草稿列（3.3.3 既有 schema）——「草稿编辑不影响线上」按
 * 「编辑不产生版本行、发布才落快照」口径验证（草稿/线上分离列演进项，偏离记录）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class PersonaSwitchIT extends AbstractIntegrationTest {

    private static final String GROUP_A = "99401";
    private static final String GROUP_B = "99402";
    private static final String BOT = "10000";

    @Autowired
    private PersonaResolver personaResolver;

    @Autowired
    private PersonaVersioningService versioningService;

    @Autowired
    private PersonaMapper personaMapper;

    @Autowired
    private PromptAssembler promptAssembler;

    @Autowired
    private MemoryEntryMapper entryMapper;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SystemParamService systemParams;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdGenerator idGenerator;

    private long personaAId;
    private long personaBId;

    private GroupKey groupA() {
        return new GroupKey("qq", BOT, GROUP_A);
    }

    @BeforeAll
    void seed() {
        for (String groupId : java.util.List.of(GROUP_A, GROUP_B)) {
            jdbcTemplate.update("DELETE FROM agent_session WHERE group_id = '" + groupId + "'");
            jdbcTemplate.update("DELETE FROM memory_entry WHERE group_id = '" + groupId + "'");
            jdbcTemplate.update("DELETE FROM user_profile WHERE group_id = '" + groupId + "'");
        }
        jdbcTemplate.update("DELETE FROM persona WHERE name IN ('it-persona-a', 'it-persona-b')");
        jdbcTemplate.update(
                "DELETE FROM content_version WHERE entity_kind = 'persona' AND entity_id IN "
                        + "(SELECT id FROM persona WHERE name IN ('it-persona-a','it-persona-b'))");
        // 两个测试人格：A 带变体与雷点素材、B 是安静人格
        personaAId = insertPersona("it-persona-a", """
                {"basicInfo": {"name": "话痨小趣"},
                 "selfSetting": "爱玩梗的群聊伙伴",
                 "personalityTraits": {"text": "好奇随和", "variants": [
                    {"variantId": "v-kek", "text": "吐槽役模式", "weight": 3},
                    {"variantId": "v-moe", "text": "软萌模式", "weight": 1}]},
                 "attentionPreference": {"talkativity": 0.8, "proactivity": 0.6},
                 "emotionConfig": {"defaultLabel": "兴奋", "sensitivity": 1.2,
                                    "semanticCues": ["被叫呆子会炸毛"]}}""");
        personaBId = insertPersona("it-persona-b", """
                {"basicInfo": {"name": "安静学者"},
                 "selfSetting": "沉稳的读书人",
                 "personalityTraits": {"text": "安静寡言"},
                 "emotionConfig": {"defaultLabel": "平静"}}""");
        systemParams.refresh();
    }

    private long insertPersona(String name, String definition) {
        PersonaDO persona = new PersonaDO();
        persona.setName(name);
        persona.setDefinition(definition);
        persona.setIsDefault(0);
        persona.setStatus("published");
        persona.setVersionNo(0);
        personaMapper.insertPersona(persona);
        PersonaDO inserted = personaMapper.selectPersonaByName(name);
        if (inserted == null) {
            throw new IllegalStateException("人格插入失败：" + name);
        }
        return inserted.getId();
    }

    private MemoryEntryDO seedMemory(String groupId, String content) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(idGenerator.nextId());
        entry.setPlatform("qq");
        entry.setBotId(BOT);
        entry.setGroupId(groupId);
        entry.setMemoryType(MemoryEntryDO.TYPE_FACT);
        entry.setContent(content);
        entry.setOccurredAt(LocalDateTime.now().minusDays(1));
        entry.setSedimentAt(LocalDateTime.now());
        entry.setStrength(50);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        entryMapper.insertEntry(entry);
        return entry;
    }

    // =====================================================================
    // 场景 G 前半：人格消费与版本留痕
    // =====================================================================

    @Test
    @Order(1)
    void systemPrompt_权重与注意力数值不进_语义雷点素材进身份层() {
        // 建会话并绑定人格 A
        sessionManager.getOrCreateSession("qq", BOT, GROUP_A);
        jdbcTemplate.update("UPDATE agent_session SET persona_id = " + personaAId
                + " WHERE group_id = '" + GROUP_A + "'");

        String assembled = promptAssembler.assemble(
                RunConfig.ExecutionForm.TURN, "qq", BOT, GROUP_A, null);

        // 语义雷点素材进身份层（供 updateEmotion 自报判断依据）
        assertThat(assembled).contains("被叫呆子会炸毛");
        // 负向：权重数值与注意力偏好参数绝不进 systemPrompt
        assertThat(assembled).doesNotContain("weight").doesNotContain("权重");
        assertThat(assembled).doesNotContain("talkativity").doesNotContain("健谈度")
                .doesNotContain("proactivity").doesNotContain("主动性");
    }

    @Test
    @Order(2)
    void 发布即快照_编辑不落版本_版本号递增() {
        versioningService.publishPersona(personaAId, "admin", "首发");
        // 编辑主表（不发布）——不产生版本行
        PersonaDO persona = personaMapper.selectPersonaByName("it-persona-a");
        persona.setDefinition(persona.getDefinition().replace("爱玩梗", "特别爱玩梗"));
        personaMapper.updatePersona(persona);

        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_version WHERE entity_kind = 'persona' AND entity_id = "
                        + personaAId, Integer.class);
        assertThat(versions).isEqualTo(1);   // 只有发布产生的快照
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version_no) FROM content_version WHERE entity_kind = 'persona' AND entity_id = "
                        + personaAId, Integer.class);
        assertThat(maxVersion).isEqualTo(1);

        versioningService.publishPersona(personaAId, "admin", "改版");
        Integer versionsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_version WHERE entity_kind = 'persona' AND entity_id = "
                        + personaAId, Integer.class);
        assertThat(versionsAfter).isEqualTo(2);
    }

    @Test
    @Order(3)
    void 回滚_旧快照写回主表_再走一次发布() {
        versioningService.rollbackPersona(personaAId, 1);

        PersonaDO persona = personaMapper.selectPersonaByName("it-persona-a");
        assertThat(persona.getDefinition()).contains("爱玩梗的群聊伙伴");
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT MAX(version_no) FROM content_version WHERE entity_kind = 'persona' AND entity_id = "
                        + personaAId, Integer.class);
        assertThat(maxVersion).isEqualTo(3);   // 回滚再发布——历史只增不减
    }

    @Test
    @Order(4)
    void 切换人格_下个Run用新人格_记忆画像保留_情绪回新基线() {
        long memoryBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = '" + GROUP_A + "'", Long.class);
        assertThat(memoryBefore).isZero();
        seedMemory(GROUP_A, "A群在人格A时期沉淀的记忆");

        personaResolver.switchPersona(groupA(), personaBId);

        PersonaDO resolved = personaResolver.resolve("qq", BOT, GROUP_A);
        assertThat(resolved.getName()).isEqualTo("it-persona-b");
        // 换人格不丢记忆：切换后记忆原样保留
        Long memoryAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = '" + GROUP_A + "'", Long.class);
        assertThat(memoryAfter).isEqualTo(1);
        // 情绪重置为人格 B 基线（平静）——JSON 列读回重序列化带空格，断言先去空白
        String emotion = jdbcTemplate.queryForObject(
                "SELECT emotion FROM agent_session WHERE group_id = '" + GROUP_A + "'", String.class);
        assertThat(emotion.replace(" ", "")).contains("\"label\":\"平静\"");
    }

    @Test
    @Order(5)
    void 多群共享同一人格_各自记忆按群隔离互不可见() {
        sessionManager.getOrCreateSession("qq", BOT, GROUP_B);
        jdbcTemplate.update("UPDATE agent_session SET persona_id = " + personaAId
                + " WHERE group_id = '" + GROUP_B + "'");
        seedMemory(GROUP_B, "B群自己的记忆");

        // 两群解析到同一人格（同一部署跑多个"同性格分身"）
        assertThat(personaResolver.resolve("qq", BOT, GROUP_A).getName()).isEqualTo("it-persona-b");
        assertThat(personaResolver.resolve("qq", BOT, GROUP_B).getName()).isEqualTo("it-persona-a");

        // 记忆按群隔离：各群只看得见自己的行
        Long groupACount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = '" + GROUP_A + "'", Long.class);
        Long groupBCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = '" + GROUP_B + "'", Long.class);
        assertThat(groupACount).isEqualTo(1);
        assertThat(groupBCount).isEqualTo(1);
        Long crossVisible = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = '" + GROUP_A
                        + "' AND content LIKE '%B群%'", Long.class);
        assertThat(crossVisible).isZero();
    }

    @Test
    @Order(6)
    void 版本切换历史可审计_content_version按版本正序可追溯() {
        java.util.List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version_no, snapshot FROM content_version WHERE entity_kind = 'persona'"
                        + " AND entity_id = " + personaAId + " ORDER BY version_no");
        assertThat(rows).hasSize(3);
        // 逐版本可追溯（v1 首发、v2 改版、v3 回滚到 v1 内容）
        String snapshot1 = String.valueOf(rows.get(0).get("snapshot"));
        String snapshot3 = String.valueOf(rows.get(2).get("snapshot"));
        assertThat(snapshot1).contains("爱玩梗的群聊伙伴");
        assertThat(snapshot3).isEqualTo(snapshot1);
    }

    /**
     * T040 回补（依赖 T026/T027 完成）：L1 供给断言——注意力偏好参数经人格偏好摘要
     * 进评估输入（健谈度等数值模型可见），systemPrompt 中无这些数值（Order 1 已负向覆盖）。
     */
    @Test
    @Order(7)
    void L1供给_注意力参数经人格偏好摘要进评估输入() {
        java.util.List<String> capturedPreference = new java.util.ArrayList<>();
        java.util.List<String> capturedPrompt = new java.util.ArrayList<>();
        cn.edu.scut.chatbot.engine.gate.L1AssessmentModel capturing =
                (prompt, digests, recentMessages, preference, groupStateDigest) -> {
                    capturedPreference.add(preference == null ? "" : preference);
                    capturedPrompt.add(prompt);
                    return java.util.List.of();
                };
        cn.edu.scut.chatbot.engine.gate.L1BatchAssessor assessor =
                new cn.edu.scut.chatbot.engine.gate.L1BatchAssessor(
                        triggerEventMapper,
                        capturing,
                        prompt -> "评估提示词",
                        (p, b, g, limit) -> java.util.List.of(),
                        (p, b, g) -> preferenceRenderer.render(new GroupKey(p, b, g)),
                        (p, b, g) -> "",
                        (p, b, g, sender) -> null,
                        (p, b, g) -> personaResolver.resolve(p, b, g).getName(),
                        clock, 0, 10);
        jdbcTemplate.update("DELETE FROM trigger_event WHERE group_id = '" + GROUP_B + "'");
        jdbcTemplate.update("INSERT INTO trigger_event (id, platform, bot_id, group_id,"
                + " trigger_kind, event_type, dedup_key, digest, decision, occurred_at) VALUES"
                + " (800101, 'qq', '" + BOT + "', '" + GROUP_B + "', 'external', 'group_message',"
                + " 'l1-supply-1', '消息', 'pending', NOW(3))");

        // B 群绑人格 A（attentionPreference talkativity 0.8 → 健谈度 80）
        assessor.assessGroup("qq", BOT, GROUP_B);

        assertThat(capturedPreference).hasSize(1);
        assertThat(capturedPreference.get(0)).contains("健谈度").contains("80");
    }

    @Autowired
    private cn.edu.scut.chatbot.engine.intake.TriggerEventMapper triggerEventMapper;

    @Autowired
    private cn.edu.scut.chatbot.memory.persona.PersonaPreferenceDigestRenderer preferenceRenderer;

    @Autowired
    private java.time.Clock clock;
}
