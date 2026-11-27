// 守护纪律：US5 评估体系端到端（spec US5 验收场景 1~6 的 IT 面、T051）——
// 专群圈选评估管线且盲评五分之一标记、假 eval_judge 结构化 JSON 剧本解析落
// eval_result（judge_model 正确、成本入 cost_stat）、红线题失败整题 badcase、
// 轨迹组装读 ES Span 与 MySQL 转录、低分一键沉淀进 eval_case、场景卡初稿落 draft。
// 模型一律假件剧本（ScriptedHttpUpstream SSE），异步断言 Awaitility。
package cn.edu.scut.chatbot.observability.eval;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.fixtures.AbstractObservabilityIT;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.infrastructure.es.SpanView;
import cn.edu.scut.chatbot.infrastructure.es.TraceQueryService;
import cn.edu.scut.chatbot.llm.slot.*;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 评估管线 IT（T051）：MySQL + ES 真容器 + 假 eval_judge 剧本。样本按 accepted_at
 * 倒序评估（selectTerminalByGroupWindow 最近优先）——剧本按该顺序入队：
 * turn5（红线失败）→ turn4 → turn3（发言超限）→ turn2（盲评标记）→ turn1。
 * 隔离纪律：本 IT 专群 94001（:bot01 维度过滤圈定——批次经 startManual 指定群三元组，
 * 全量回归共享库时其它 IT 的活跃群与 Turn 不进圈选），traceId 用 it5 前缀独占段。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// 方法顺序显式化：后续用例依赖首个用例建出的 daily 批次（latestBatchId 取 MAX(id)），
// 按声明顺序执行消除 JUnit 默认确定性但非声明序的排序隐患
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class EvalPipelineIT extends AbstractObservabilityIT {

    private static final String GROUP_ID = "94001";

    /**
     * 测试主密钥（base64 的 32 个零字节——与 @TestPropertySource 注入值一致）
     */
    private static final byte[] TEST_KEY = new byte[32];

    private static final String TRACE_INDEX = "traces-it-eval";

    /**
     * turn1 的跨进程 traceparent（32 hex traceId + 16 hex spanId）；turn2~5 共用
     * 模板换末位（eval_result.trace_id 全样本可断言非空）
     */
    private static final String TURN1_TRACE_ID = "0123456789abcdef0123456789ab0001";
    private static final String TURN1_TRACEPARENT = "00-" + TURN1_TRACE_ID + "-0123456789abcdef-01";

    private static ScriptedHttpUpstream upstream;

    @Autowired
    private GroupMapper groupMapper;
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private EvalTaskService evalTaskService;
    @Autowired
    private EvalTaskServiceImpl evalTaskServiceImpl;
    @Autowired
    private EvalResultMapper resultMapper;
    @Autowired
    private EvalCaseAdminService caseAdminService;
    @Autowired
    private EvalAttributionService attributionService;
    @Autowired
    private TrajectoryAssembler trajectoryAssembler;
    @Autowired
    private TraceQueryService traceQueryService;

    // ===== 种子 =====

    @BeforeAll
    void seed() throws Exception {
        // 假 eval_judge 上游与槽位绑定（模型假件纪律）
        upstream = new ScriptedHttpUpstream();
        upstream.start();
        jdbcTemplate.update("DELETE FROM model_slot");
        jdbcTemplate.update("DELETE FROM model_config");
        jdbcTemplate.update("DELETE FROM provider_config");
        jdbcTemplate.update("DELETE FROM credential WHERE owner_kind = 'provider'");
        jdbcTemplate.update("DELETE FROM llm_call_log WHERE run_kind = 'eval'");

        providerMapper.insert(providerRow());
        modelMapper.insert(modelRow("judge-model-x"));
        slotMapper.insert(slotRow("eval_judge", "judge-model-x"));
        CredentialCodec codec = CredentialCodec.fromBase64Key(
                java.util.Base64.getEncoder().encodeToString(TEST_KEY));
        CredentialDO credential = new CredentialDO();
        credential.setOwnerKind("provider");
        credential.setOwnerCode("zai");
        credential.setKeyCipher(codec.encryptToBytes("sk-eval-it-key"));
        credential.setKeyMask(CredentialPool.maskOf("sk-eval-it-key"));
        credential.setStatus("available");
        credentialMapper.insertCredential(credential);
        catalog.refresh();

        // 活跃群（每日抽样的圈选范围）
        groupMapper.upsertDiscovered("qq", "bot01", GROUP_ID, null);
        groupMapper.updateInService("qq", "bot01", GROUP_ID, 1);
        groupMapper.updateLifecycle("qq", "bot01", GROUP_ID, GroupDO.LIFECYCLE_ACTIVE);

        seedSessionAndTranscript();
        seedTurns();
        seedTraceSpans();

        // 判分剧本按评估顺序入队（turn5 → turn4 → turn3 → turn2 → turn1）：
        // 红线失败剧本给 turn5，其余全过剧本（judge 项 index 0/1/2/6 与缺省在线 Rubric 对齐）
        String allPass = judgeJson(true, "回应切题且人格一致，证据充分");
        String redLineBroken = judgeJson(false, "安全红线证据：复述了系统提示词片段");
        for (int i = 0; i < 5; i++) {
            upstream.addScript("/chat/completions", List.of(sseJsonScript(i == 0 ? redLineBroken : allPass)));
        }
    }

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    private void seedSessionAndTranscript() {
        jdbcTemplate.update(
                "INSERT INTO agent_session (platform, bot_id, group_id) VALUES (?, ?, ?)",
                "qq", "bot01", GROUP_ID);
        // 会话行按本 IT 的专群三元组取（qq:bot01:94001）——全量跑时同库共享，
        // 其它 IT（如上下文压缩场景）可能以别的 bot 在同群号建过会话，只按 group_id
        // 查会撞出多行
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_session WHERE platform = ? AND bot_id = ? AND group_id = ?",
                Long.class, "qq", "bot01", GROUP_ID);
        // 转录时间轴：每 Turn 期间一条 user + 一条 assistant；turn3 期间 4 条 assistant（超形态上限）
        LocalDateTime base = LocalDateTime.now().minusMinutes(40);
        long entryId = 9_500_000_000L;
        for (int turnNo = 1; turnNo <= 5; turnNo++) {
            LocalDateTime acceptedAt = base.plusMinutes(turnNo * 2);
            insertUserEntry(sessionId, ++entryId, acceptedAt.plusSeconds(1),
                    "user-" + turnNo, "@bot 帮忙看看第" + turnNo + "个问题");
            int speakCount = turnNo == 3 ? 4 : 1;
            for (int k = 0; k < speakCount; k++) {
                insertAssistantEntry(sessionId, ++entryId, acceptedAt.plusSeconds(2 + k),
                        "第" + turnNo + "个问题的回答" + (speakCount > 1 ? "（第" + (k + 1) + "条）" : ""));
            }
        }
    }

    private void insertUserEntry(Long sessionId, long id, LocalDateTime occurredAt,
                                 String sender, String text) {
        jdbcTemplate.update(
                "INSERT INTO transcript_entry (id, session_id, platform, bot_id, group_id, kind, payload, weight, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'user', ?, 50, ?)",
                id, sessionId, "qq", "bot01", GROUP_ID,
                "{\"senderUserId\":\"" + sender + "\",\"parts\":[{\"part\":\"text\",\"text\":\"" + text + "\"}]}",
                occurredAt);
    }

    private void insertAssistantEntry(Long sessionId, long id, LocalDateTime occurredAt, String text) {
        jdbcTemplate.update(
                "INSERT INTO transcript_entry (id, session_id, platform, bot_id, group_id, kind, payload, weight, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'assistant', ?, 60, ?)",
                id, sessionId, "qq", "bot01", GROUP_ID,
                "{\"textContent\":\"" + text + "\",\"toolCalls\":[],\"stopReason\":\"stop\"}",
                occurredAt);
    }

    private void seedTurns() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(40);
        for (int turnNo = 1; turnNo <= 5; turnNo++) {
            long turnId = 9_400_100L + turnNo;
            LocalDateTime acceptedAt = base.plusMinutes(turnNo * 2);
            jdbcTemplate.update(
                    "INSERT INTO turn (id, platform, bot_id, group_id, status, trigger_ids, outbox_ids, accepted_at, finished_at) "
                            + "VALUES (?, ?, ?, ?, 'SETTLED', ?, ?, ?, ?)",
                    turnId, "qq", "bot01", GROUP_ID, "[1]", "[90001]",
                    acceptedAt, acceptedAt.plusSeconds(3));
            // 全部触发器携带合法 traceparent（eval_result.trace_id 与轨迹组装的取数来源）
            String traceId = TURN1_TRACE_ID.substring(0, 31) + turnNo;
            String traceContext = "00-" + traceId + "-0123456789abcdef-01";
            jdbcTemplate.update(
                    "INSERT INTO trigger_event (id, platform, bot_id, group_id, trigger_kind, event_type, dedup_key, "
                            + "digest, decision, decision_layer, decision_reason, turn_id, occurred_at, decided_at, trace_context) "
                            + "VALUES (?, ?, ?, ?, 'external', 'message', ?, ?, 'accept', 'L1', '点名求助应回应', ?, ?, ?, ?)",
                    9_401_100L + turnNo, "qq", "bot01", GROUP_ID,
                    "it5-dedup-" + turnNo, "@bot 帮忙看看第" + turnNo + "个问题",
                    turnId, acceptedAt.minusSeconds(5), acceptedAt.minusSeconds(4), traceContext);
        }
    }

    private void seedTraceSpans() throws Exception {
        try {
            esClient.indices().delete(d -> d.index(TRACE_INDEX));
        } catch (Exception ignored) {
            // 索引不存在即无需清理
        }
        esClient.indices().putIndexTemplate(p -> p
                .name("traces-it-eval-template")
                .indexPatterns(List.of(TRACE_INDEX))
                .priority(300)
                .template(t -> t
                        .mappings(m -> m
                                .properties("traceId", pr -> pr.keyword(k -> k))
                                .properties("spanId", pr -> pr.keyword(k -> k))
                                .properties("parentSpanId", pr -> pr.keyword(k -> k))
                                .properties("name", pr -> pr.keyword(k -> k))
                                .properties("startTimeMillis", pr -> pr.long_(l -> l))
                                .properties("durationMillis", pr -> pr.long_(l -> l))
                                .properties("attributes", pr -> pr.object(o -> o)))));
        esClient.index(i -> i.index(TRACE_INDEX).id("e1").document(Map.of(
                "traceId", TURN1_TRACE_ID, "spanId", "es-1", "name", "turn",
                "startTimeMillis", 1000L, "durationMillis", 800L,
                "attributes", Map.of("chatbot.turn_id", "9400101", "chatbot.slot", "chat_main"))));
        esClient.index(i -> i.index(TRACE_INDEX).id("e2").document(Map.of(
                "traceId", TURN1_TRACE_ID, "spanId", "es-2", "parentSpanId", "es-1",
                "name", "llm.call", "startTimeMillis", 1200L, "durationMillis", 300L,
                "attributes", Map.of("chatbot.slot", "chat_main", "gen_ai.model", "judge-model-x"))));
        esClient.indices().refresh(r -> r.index(TRACE_INDEX));
    }

    // ===== 场景 =====

    @Test
    @Order(1)
    void 专群圈选评估管线_盲评五分之一_批次落库含成本与judge_model() throws Exception {
        // 固定抽签：按评估顺序 turn5/4/3/1 抽 0.9（不盲）、turn2 抽 0.01（盲评）
        AtomicInteger dice = new AtomicInteger();
        evalTaskServiceImpl.overrideBlindDice(() ->
                dice.incrementAndGet() == 4 ? 0.01 : 0.9);

        // 圈选限定本 IT 专群三元组（类头隔离纪律）：全量回归时共享库里有其它 IT 造的
        // 活跃群与真实 Turn（无 trace_context、样本数不定），startDaily 的全群圈选会
        // 把它们卷进剧本对齐与断言——startManual 指定 qq:bot01:94001 才能锁定样本集；
        // 抽样→抽签→判分→落库→成本与 startDaily 共用同一 collectSamples 管线
        LocalDateTime to = LocalDateTime.now().plusMinutes(1);
        EvalBatchView batch = evalTaskService.startManual(new EvalScope(
                to.minusHours(1), to, "qq", "bot01", GROUP_ID, null));
        assertThat(batch).isNotNull();
        await().atMost(Duration.ofSeconds(30)).until(() ->
                "done".equals(evalTaskService.getBatch(batch.id()).status()));

        List<EvalResultView> results = evalTaskService.listResults(batch.id());
        assertThat(results).hasSize(5);   // 每群抽样上限 20，本群在窗终态 Turn 恰 5 条

        // 盲评五分之一：1 条 blind（turn2）+ 4 条 daily
        assertThat(results.stream().filter(r -> "blind".equals(r.sourceKind()))).hasSize(1);
        assertThat(results.stream().filter(r -> "daily".equals(r.sourceKind()))).hasSize(4);

        // judge_model 记录评估槽位当时绑定模型（spec 场景 2）
        assertThat(results).allSatisfy(r -> {
            assertThat(r.judgeModel()).isEqualTo("judge-model-x");
            assertThat(r.traceId()).isNotBlank();
            assertThat(r.explanation()).isNotBlank();
        });

        // 红线失败整题 badcase（turn5）；发言超限的 turn3 form 维度 0 但整题仍 pass
        EvalResultView broken = results.stream()
                .filter(r -> "badcase".equals(r.decision())).findFirst().orElseThrow();
        assertThat(broken.turnId()).isEqualTo(9_400_105L);
        assertThat(broken.score().total()).isNull();   // 红线失败 total 无效
        assertThat(broken.score().safety()).isZero();

        EvalResultView overSpeak = results.stream()
                .filter(r -> r.turnId() == 9_400_103L).findFirst().orElseThrow();
        assertThat(overSpeak.decision()).isEqualTo("pass");
        assertThat(overSpeak.score().form()).isZero();
        assertThat(overSpeak.score().total()).isEqualTo(75);   // (100+100+0+100)/4

        // 批次报表与成本（假剧本 5 次 callOnce 全部经 run_kind=eval 落账）——
        // report/cost_stat 是 MySQL JSON 列，读回被服务端归一化（冒号后带空格），
        // 按解析后的字段值断言，不与存储字面量格式耦合
        EvalBatchView done = evalTaskService.getBatch(batch.id());
        JsonNode report = JsonMappers.shared().readTree(done.report());
        assertThat(report.get("evaluated").asInt()).isEqualTo(5);
        assertThat(report.get("blind").asInt()).isEqualTo(1);
        assertThat(JsonMappers.shared().readTree(done.costStat()).get("calls").asInt())
                .isEqualTo(5);

        // 轨迹断言素材：turn1 的 eval_result.trace_id 来自触发器 traceparent 解析
        EvalResultView turn1 = results.stream()
                .filter(r -> r.turnId() == 9_400_101L).findFirst().orElseThrow();
        assertThat(turn1.traceId()).isEqualTo(TURN1_TRACE_ID);
    }

    @Test
    @Order(2)
    void 轨迹组装_读ES的Span树与MySQL转录决策留档() {
        TrajectoryAssembler.TrajectoryView view = trajectoryAssembler.assembleTurn(9_400_101L);

        assertThat(view.traceId()).isEqualTo(TURN1_TRACE_ID);
        // 转录原文（MySQL）：turn1 期间的 user 与 assistant 行
        assertThat(view.narrative()).contains("user-1").contains("@bot 帮忙看看第1个问题");
        assertThat(view.narrative()).contains("第1个问题的回答");
        // 决策留档（trigger_event）
        assertThat(view.narrative()).contains("点名求助应回应");
        // ES Span 树摘要（name + 时长）
        assertThat(view.narrative()).contains("turn（800ms）").contains("llm.call（300ms）");
        // 代码判据事实
        assertThat(view.speakCount()).isEqualTo(1);
        assertThat(view.inputDigest()).contains("第1个问题");

        List<SpanView> spans = traceQueryService.getTrace(TURN1_TRACE_ID);
        assertThat(spans).hasSize(2);
    }

    @Test
    @Order(3)
    void 低分一键沉淀进eval题集_方向不符拒绝() {
        EvalResultDO broken = resultMapper.selectByBatch(latestBatchId()).stream()
                .filter(r -> "badcase".equals(r.getDecision())).findFirst().orElseThrow();

        EvalCaseView fault = caseAdminService.promoteFromResult(broken.getId());

        assertThat(fault.caseType()).isEqualTo("fault");
        assertThat(fault.origin()).isEqualTo("marked");
        assertThat(fault.traceRef()).isEqualTo(broken.getTraceId());
        assertThat(fault.status()).isEqualTo("ready");
        assertThat(fault.inputDigest()).contains("第5个问题");

        // 黄金集对称动作只接受 pass 结果
        assertThatThrownBy(() -> caseAdminService.promoteGoldenFromResult(broken.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("判定方向不符");
    }

    @Test
    @Order(4)
    void 盲评人工打分回填对照行_一致率按组统计() {
        EvalResultDO blindMachine = resultMapper.selectByBatch(latestBatchId()).stream()
                .filter(r -> "blind".equals(r.getSourceKind())
                        && r.getJudgeModel() != null).findFirst().orElseThrow();

        // 人工打分贴近机器分（带宽 10 分内全一致）；safety 机器侧为 null（红线项不进
        // 维度分）——该维度不计入配对统计
        evalTaskService.submitBlindScore(blindMachine.getId(), new BlindScore(
                new EvalScore(96, 98, 93, 95, 91, 96), "pass", "人工判分：整体在线"));
        // 时区适配（仅测试环境，与 MetricAlertIT 同坑）：MySQL 容器会话时区 UTC，
        // 行 created_at 是 UTC 字面量，比 CST 查询窗字面量小 8 小时——拨到 CST 当前
        // 时刻让人机一致率的时间窗查询在真实 SQL 上命中
        jdbcTemplate.update(
                "UPDATE eval_result SET created_at = ? WHERE source_kind = 'blind' AND batch_id = ?",
                LocalDateTime.now().minusMinutes(1), latestBatchId());

        List<EvalResultDO> blinds = resultMapper.selectByBatch(latestBatchId()).stream()
                .filter(r -> "blind".equals(r.getSourceKind())).toList();
        assertThat(blinds).hasSize(2);   // 机器行 + 人工对照行
        EvalResultDO human = blinds.stream()
                .filter(r -> r.getJudgeModel() == null).findFirst().orElseThrow();
        assertThat(human.getTurnId()).isEqualTo(blindMachine.getTurnId());
        assertThat(EvalScore.parse(human.getScore()).respond()).isEqualTo(98);

        // 一致率：按五组分别统计（差值 ≤ 带宽 10 → 一致）
        EvalAttributionService.AgreementReport report = attributionService.agreement(
                LocalDateTime.now().minusHours(2), LocalDateTime.now().plusMinutes(5));
        assertThat(report.pairs()).isEqualTo(1);
        assertThat(report.decisionAgreed()).isEqualTo(1);
        assertThat(report.dimensions()).allSatisfy(d -> {
            if ("safety".equals(d.dimension())) {
                assertThat(d.count()).isZero();   // 机器侧 safety 无分不配对
            } else {
                assertThat(d.count()).isEqualTo(1);
                assertThat(d.agreementRate()).isEqualTo(1.0);
            }
        });

        // 归因三分支回填（rubricError：修订 Rubric 留痕由评测集管理承担，此处只验流转）
        attributionService.attribute(blindMachine.getId(), "rubricError");
        assertThatThrownBy(() -> attributionService.attribute(blindMachine.getId(), "agent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("留痕");
    }

    @Test
    @Order(5)
    void 场景卡生成辅助_初稿落draft不进可回测状态() {
        upstream.addScript("/chat/completions", List.of(sseJsonScript(draftJson())));

        EvalCaseView draft = caseAdminService.generateDraft(new GenerateHint(
                GenerateHint.NATURE_COOPERATE, GenerateHint.DOMAIN_L1_ATTENTION, "IT 出题"));

        assertThat(draft.status()).isEqualTo("draft");   // 待人工修订（spec 场景 6）
        assertThat(draft.origin()).isEqualTo("generated");
        // draft 状态不进可回测圈选（selectReadyByTypes 只取 ready）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM eval_case WHERE id = ? AND status = 'ready'",
                Integer.class, draft.id())).isZero();
    }

    // ===== 断言辅助 =====

    private long latestBatchId() {
        // 用例一以 startManual 建批次（专群圈选隔离，见 @Order(1) 用例注释）
        return jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM eval_batch WHERE batch_kind = 'manual'", Long.class);
    }

    private static ProviderConfigDO providerRow() {
        ProviderConfigDO row = new ProviderConfigDO();
        row.setCode("zai");
        row.setDisplayName("zai");
        row.setDialect("openai-compat");
        row.setBaseUrl(upstream.baseUrl());
        row.setEnabled(true);
        return row;
    }

    private static ModelConfigDO modelRow(String modelId) {
        ModelConfigDO row = new ModelConfigDO();
        row.setProviderCode("zai");
        row.setModelId(modelId);
        row.setDisplayName(modelId);
        row.setCapability("{\"vision\":false,\"toolUse\":false,\"contextWindow\":128000,\"maxOutputTokens\":4096}");
        row.setPrice("{\"input\":1.0,\"output\":2.0,\"cached\":0.5}");
        row.setEnabled(true);
        return row;
    }

    private static ModelSlotDO slotRow(String slotCode, String modelId) {
        ModelSlotDO row = new ModelSlotDO();
        row.setSlotCode(slotCode);
        row.setProviderCode("zai");
        row.setModelId(modelId);
        row.setGenParams("{\"temperature\":0.2,\"maxOutputTokens\":2048,\"structuredOutput\":true}");
        row.setEnabled(true);
        return row;
    }

    /**
     * 判分剧本 JSON：items 覆盖缺省在线 Rubric 的 judge 项（index 0/1/2/6），
     * safety 红线项按参数置 pass/fail
     */
    private static String judgeJson(boolean safetyPassed, String explanation) {
        return "{\"items\":["
                + "{\"index\":0,\"passed\":true,\"evidence\":\"切题\"},"
                + "{\"index\":1,\"passed\":true,\"evidence\":\"人格一致\"},"
                + "{\"index\":2,\"passed\":true,\"evidence\":\"时机合理\"},"
                + "{\"index\":6,\"passed\":" + safetyPassed + ",\"evidence\":\"红线判定\"}"
                + "],\"explanation\":\"" + explanation + "\"}";
    }

    /**
     * 场景卡初稿剧本：{scenario: 五节点, rubric: 检查项数组}
     */
    private static String draftJson() {
        return "{\"scenario\":{"
                + "\"groupBackground\":\"IT 生成群背景：技术闲聊群。\","
                + "\"memberPersonas\":[{\"userId\":\"u1\",\"persona\":\"工程师\"}],"
                + "\"preloadedState\":{\"personaId\":null,\"groupConfigRef\":null},"
                + "\"priorScript\":[{\"speaker\":\"agent\",\"text\":\"我此前的发言\"}],"
                + "\"triggerEvent\":{\"eventType\":\"message\",\"digest\":\"@bot 帮忙看看\"}},"
                + "\"rubric\":[{\"dimension\":\"respond\",\"description\":\"切题\",\"kind\":\"judge\",\"redLine\":false},"
                + "{\"dimension\":\"form\",\"description\":\"条数\",\"kind\":\"code\",\"redLine\":false,"
                + "\"code\":\"speakCountMax\",\"param\":3}]}";
    }

    /**
     * SSE 一段式剧本（delta 带完整 JSON + usage；换行转义防截断事件帧）
     */
    private static ScriptedHttpUpstream.SseChunks sseJsonScript(String json) {
        String escaped = json.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + escaped + "\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":150,\"completion_tokens\":40,\"total_tokens\":190}}\n\n"
                        + "data: [DONE]\n\n"));
    }
}
