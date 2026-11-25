// 守护纪律：工单不丢（压缩同事务落单）、崩溃续跑不重复（两级幂等）、双存储次序
// MySQL 先落 Qdrant 后写、抽取容错跳批由兜底补、强化/衰减/归档/取代/总量控制全链路
// （quickstart 场景 A；contracts/sediment-pipeline.md 全部纪律）
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.context.compaction.TranscriptCompactor;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.DeterministicEmbeddings;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.llm.slot.CredentialDO;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.llm.slot.CredentialPool;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.ModelConfigDO;
import cn.edu.scut.chatbot.llm.slot.ModelConfigMapper;
import cn.edu.scut.chatbot.llm.slot.ModelSlotDO;
import cn.edu.scut.chatbot.llm.slot.ModelSlotMapper;
import cn.edu.scut.chatbot.llm.slot.ProviderConfigDO;
import cn.edu.scut.chatbot.llm.slot.ProviderConfigMapper;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.retrieve.CollectionGuard;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import cn.edu.scut.chatbot.memory.sediment.SedimentFallbackScanner;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobDO;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobMapper;
import cn.edu.scut.chatbot.memory.sediment.SedimentWorker;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 沉淀管线端到端 IT（quickstart 场景 A）：真 MySQL + Redis + Qdrant + 脚本化模型假上游
 * （embedding 确定性伪向量、summarize 抽取 JSON 剧本）。worker 的 @Scheduled 轮询以
 * claimBatch=0 静默（脚本按工单顺序回放需要确定性消费），poll 路径在崩溃续跑用例显式恢复。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class SedimentPipelineIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "95001";

    private static ScriptedHttpUpstream upstream;

    private static final Map<String, float[]> VECTOR_OVERRIDES = new ConcurrentHashMap<>();

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private TranscriptCompactor compactor;

    @Autowired
    private SedimentWorker worker;

    @Autowired
    private SedimentFallbackScanner scanner;

    @Autowired
    private SedimentJobMapper jobMapper;

    @Autowired
    private MemoryEntryMapper entryMapper;

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

    @Autowired
    private CollectionGuard collectionGuard;

    @BeforeAll
    void seed() throws Exception {
        upstream = new ScriptedHttpUpstream();
        upstream.addDynamicScript("/embeddings", request ->
                DeterministicEmbeddings.embeddingsResponseFor(request, 8, VECTOR_OVERRIDES));
        upstream.start();

        // 清残留（容器共享单例）——记忆、工单、画像、集合、登记、槽位
        jdbcTemplate.update("DELETE FROM memory_entry WHERE group_id = '" + TEST_GROUP + "'");
        jdbcTemplate.update("DELETE FROM sediment_job WHERE group_id = '" + TEST_GROUP + "'");
        jdbcTemplate.update("DELETE FROM user_profile WHERE group_id = '" + TEST_GROUP + "'");
        jdbcTemplate.update("DELETE FROM transcript_entry WHERE group_id = '" + TEST_GROUP + "'");
        jdbcTemplate.update("DELETE FROM agent_session WHERE group_id = '" + TEST_GROUP + "'");
        deleteQdrantCollection("memory_entry_v1");
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"collection\":\"memory_entry\",\"dim\":null,\"embedModelCode\":null}'"
                        + " WHERE param_key = 'memory.qdrant.collection_meta'");
        // worker 轮询静默（claimBatch=0）：脚本按工单顺序回放需要确定性消费
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"claimBatch\":0,\"processingTimeoutMinutes\":30,\"retryMax\":3,\"keepDoneDays\":30}'"
                        + " WHERE param_key = 'memory.sediment.worker'");
        jdbcTemplate.update("DELETE FROM model_slot WHERE slot_code IN ('embedding', 'summarize')");
        jdbcTemplate.update("DELETE FROM model_config WHERE model_id IN ('embedding-it', 'summarize-it')");
        jdbcTemplate.update("DELETE FROM provider_config WHERE code = 'zai-it'");
        jdbcTemplate.update("DELETE FROM credential WHERE owner_kind = 'provider' AND owner_code = 'zai-it'");

        providerMapper.insert(providerRow());
        modelMapper.insert(modelRow("embedding-it", false));
        modelMapper.insert(modelRow("summarize-it", true));
        slotMapper.insert(slotRow(ModelCatalog.SLOT_EMBEDDING, "embedding-it"));
        slotMapper.insert(slotRow(ModelCatalog.SLOT_SUMMARIZE, "summarize-it"));

        CredentialCodec codec = CredentialCodec.fromBase64Key(
                Base64.getEncoder().encodeToString(new byte[32]));
        CredentialDO credential = new CredentialDO();
        credential.setOwnerKind("provider");
        credential.setOwnerCode("zai-it");
        credential.setKeyCipher(codec.encryptToBytes("sk-sediment-0001"));
        credential.setKeyMask(CredentialPool.maskOf("sk-sediment-0001"));
        credential.setStatus("available");
        credentialMapper.insertCredential(credential);
        catalog.refresh();
        systemParams.refresh();
        // 启动自检跑在槽位绑定之前（集合创建被推迟），槽位就绪后显式补建集合——
        // 否则 Qdrant 404 在客户端按「索引层可失败」静默吞掉，去重近邻恒为空
        collectionGuard.ensureCollection();
    }

    @AfterAll
    void stopUpstream() {
        if (upstream != null) {
            upstream.close();
        }
        // 容器与参数表跨测试类共享——恢复用例内拨动的共享参数到种子值
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"claimBatch\":10,\"processingTimeoutMinutes\":30,\"retryMax\":3,\"keepDoneDays\":30}'"
                        + " WHERE param_key = 'memory.sediment.worker'");
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"archiveThreshold\":15,\"archiveKeepDays\":180,\"groupSoftLimit\":5000,\"dailyDecayRate\":1.0}'"
                        + " WHERE param_key = 'memory.decay.strength'");
    }

    // =====================================================================
    // 夹具
    // =====================================================================

    private AgentSessionDO session() {
        return sessionManager.getOrCreateSession("qq", "10000", TEST_GROUP);
    }

    /**
     * 追加 n 条群友消息（含批次标记词——抽取剧本按词选回放分支之外的确定性锚）
     */
    private void appendMessages(int count, String markerWord) {
        List<AgentMessage> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new UserMessage(idGenerator.nextIdString(),
                    Instant.ofEpochMilli(1775000000000L + i * 60_000L), 50, null,
                    i % 2 == 0 ? "u1" : "u2",
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                            cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT,
                            markerWord + "李四是程序员，张三喜欢原神" + i))));
        }
        sessionManager.appendTranscript(session(), batch, null);
    }

    /**
     * 触发一次压缩（水位超线）并返回落库工单
     */
    private SedimentJobDO compactOnce() {
        compactor.compactIfNeeded(session(), 30000, 8000);
        List<SedimentJobDO> jobs = jobMapper.selectByGroup("qq", "10000", TEST_GROUP);
        assertThat(jobs).isNotEmpty();
        return jobs.get(jobs.size() - 1);
    }

    /**
     * 认领并同步处理一张工单（确定性消费——不走 poll 的并发派发）
     */
    private void processJob(SedimentJobDO job) {
        int claimed = jobMapper.claimJob(job.getId(), 3);
        assertThat(claimed).isPositive();
        worker.process(job);
    }

    /**
     * 抽取剧本（SSE 一段式：delta 带完整 JSON + usage）。
     * 注意换行必须转义——SSE data 行内出现裸换行会截断事件帧
     */
    private static ScriptedHttpUpstream.SseChunks extractionScript(String json) {
        return sseTextChunk(json);
    }

    /**
     * 文本剧本（压缩摘要的 ModelSummarizer 也走 summarize 槽位——每批次需要两份剧本：
     * 第一份给压缩摘要、第二份给工单抽取，按消费顺序入队）
     */
    private static ScriptedHttpUpstream.SseChunks sseTextChunk(String text) {
        String escaped = text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return new ScriptedHttpUpstream.SseChunks(List.of(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + escaped + "\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150}}\n\n"
                        + "data: [DONE]\n\n"));
    }

    /**
     * 一次批次的完整剧本对：压缩摘要 + 工单抽取（消费顺序：先摘要后抽取）
     */
    private void registerBatchScripts(String extractionJson) {
        upstream.addScript("/chat/completions", List.of(sseTextChunk("本批讨论了李四的职业和张三的游戏偏好。")));
        upstream.addScript("/chat/completions", List.of(extractionScript(extractionJson)));
    }

    private static final String BATCH1_EXTRACTION = """
            {"candidateEntries":[
              {"memoryType":"fact","content":"李四是程序员","userId":"u1",
               "occurredAtHint":"2026-11-20","keywords":[],"mergedContent":null},
              {"memoryType":"preference","content":"张三喜欢原神","userId":"u2",
               "occurredAtHint":"2026-11-20","keywords":[],"mergedContent":null}],
             "profileSignals":[
              {"userId":"u2","callName":"三哥","interests":["原神"],"relationClue":null}],
             "conflicts":[]}""";

    private static final String BATCH2_MERGE_EXTRACTION = """
            {"candidateEntries":[
              {"memoryType":"fact","content":"李四是程序员","userId":"u1",
               "occurredAtHint":"2026-11-21","keywords":[],
               "mergedContent":"李四是程序员（群里常聊起）"}],
             "profileSignals":[],"conflicts":[]}""";

    private static final String BATCH3_CONFLICT_EXTRACTION = """
            {"candidateEntries":[
              {"memoryType":"preference","content":"张三改玩星穹铁道","userId":"u2",
               "occurredAtHint":"2026-11-22","keywords":[],"mergedContent":null}],
             "profileSignals":[],"conflicts":[
              {"existingContent":"张三喜欢原神","newContent":"张三改玩星穹铁道",
               "reason":"兴趣变化"}]}""";

    private static final String BATCH4_INVALID = "这不是合法的抽取输出";

    // =====================================================================
    // 场景 A 主链路
    // =====================================================================

    @Test
    @Order(1)
    void 压缩触发_同事务落工单_worker消费_双存储落库_工单done回填() throws Exception {
        appendMessages(60, "批次甲");
        registerBatchScripts(BATCH1_EXTRACTION);
        SedimentJobDO job = compactOnce();

        // 压缩成功必有工单：幂等键 = summaryEntryId，trigger_kind = compress
        assertThat(job.getTriggerKind()).isEqualTo(SedimentJobDO.KIND_COMPRESS);
        assertThat(job.getIdempotentKey()).isNotBlank();
        assertThat(job.getStatus()).isEqualTo(SedimentJobDO.STATUS_PENDING);

        processJob(job);

        // MySQL 落库（source_ref 回指工单）
        MemoryEntryDO fact = findEntry("李四是程序员");
        assertThat(fact).isNotNull();
        assertThat(fact.getMemoryType()).isEqualTo(MemoryEntryDO.TYPE_FACT);
        assertThat(fact.getUserId()).isEqualTo("u1");
        assertThat(fact.getStrength()).isEqualTo(50);
        // source_ref 是 MySQL JSON 列：读回时被重新序列化（冒号后带空格），断言对空白不敏感
        assertThat(fact.getSourceRef().replace(" ", "")).contains("\"jobId\":" + job.getId());

        // 工单 done 回填 output_ref（含新建条目清单）
        SedimentJobDO done = jobMapper.selectById(job.getId());
        assertThat(done.getStatus()).isEqualTo(SedimentJobDO.STATUS_DONE);
        assertThat(done.getOutputRef()).contains("created");

        // Qdrant 后写（await 异步向量化）：点 id = 条目 id、payload 含隔离三元组、不含 strength
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Long> ids = qdrantSearchIds("李四是程序员");
            assertThat(ids).first().isEqualTo(fact.getId());
        });
        JsonNode payload = qdrantPointPayload(fact.getId());
        assertThat(payload.path("groupId").asText()).isEqualTo(TEST_GROUP);
        assertThat(payload.path("memoryType").asText()).isEqualTo(MemoryEntryDO.TYPE_FACT);
        assertThat(payload.has("strength")).isFalse();
        assertThat(payload.has("status")).isFalse();

        // 画像增量同批应用 user_profile、output_ref 记录画像变更
        Map<String, Object> profile = jdbcTemplate.queryForMap(
                "SELECT call_name, interest_tags FROM user_profile WHERE group_id = ? AND user_id = 'u2'",
                TEST_GROUP);
        assertThat(profile.get("call_name")).isEqualTo("三哥");
        assertThat(String.valueOf(profile.get("interest_tags"))).contains("原神");
        assertThat(done.getOutputRef()).contains("三哥");
    }

    @Test
    @Order(2)
    void 幂等键重复投递_唯一索引拒插_不产生重复工单() {
        SedimentJobDO existing = latestJob();
        SedimentJobDO duplicate = new SedimentJobDO();
        duplicate.setPlatform("qq");
        duplicate.setBotId("10000");
        duplicate.setGroupId(TEST_GROUP);
        duplicate.setTriggerKind(SedimentJobDO.KIND_COMPRESS);
        duplicate.setIdempotentKey(existing.getIdempotentKey());
        duplicate.setPayload(existing.getPayload());
        duplicate.setStatus(SedimentJobDO.STATUS_PENDING);
        duplicate.setRetryCount(0);
        org.assertj.core.api.Assertions.assertThatCode(() -> jobMapper.insertJob(duplicate))
                .hasMessageContaining("uk_idem");
        // 工单数不变
        assertThat(jobMapper.selectByGroup("qq", "10000", TEST_GROUP)).hasSize(1);
    }

    @Test
    @Order(3)
    void 相近候选判合并_既有条目强化与正文扩充_不新建() {
        appendMessages(40, "批次乙");
        registerBatchScripts(BATCH2_MERGE_EXTRACTION);
        SedimentJobDO job = compactOnce();
        processJob(job);

        // 同文本同哈希向量 → 相似度 1.0 ≥ 0.85 → 合并：不新建行
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = ? AND content LIKE '李四是程序员%'",
                Long.class, TEST_GROUP)).isEqualTo(1);
        // 正文按 mergedContent 拼接重写
        assertThat(findEntry("李四是程序员（群里常聊起）")).isNotNull();
        // 强化 +10
        assertThat(findEntry("李四是程序员（群里常聊起）").getStrength()).isEqualTo(60);

        SedimentJobDO done = jobMapper.selectById(job.getId());
        assertThat(done.getOutputRef()).contains("merged");
    }

    @Test
    @Order(4)
    void 抽取非法JSON_本批跳过告警_工单failed_主流程不受影响() {
        appendMessages(40, "批次丙");
        // BATCH4_INVALID 本身就是非法文本：摘要剧本是合法文本不受影响，抽取剧本非法正好触发本用例
        registerBatchScripts(BATCH4_INVALID);
        SedimentJobDO job = compactOnce();
        processJob(job);

        assertThat(jobMapper.selectById(job.getId()).getStatus())
                .isEqualTo(SedimentJobDO.STATUS_FAILED);
        // 主流程不受影响：无新记忆条目（张三改玩星穹铁道尚未进库——留下批验证）
        assertThat(findEntry("张三改玩星穹铁道")).isNull();

        // 兜底重投（failed 未超限可直接再认领）：注册合法剧本后重跑同一工单成功
        upstream.addScript("/chat/completions", List.of(extractionScript(BATCH3_CONFLICT_EXTRACTION)));
        jobMapper.resetForManualRetry(job.getId());
        processJob(jobMapper.selectById(job.getId()));
        assertThat(jobMapper.selectById(job.getId()).getStatus()).isEqualTo(SedimentJobDO.STATUS_DONE);
    }

    @Test
    @Order(5)
    void 冲突取代_旧条superseded互链指向新条() {
        // 旧条已被取代（status=superseded），selectActiveByGroup 查不到——按内容直查（不限状态）
        MemoryEntryDO fresh = findEntry("张三改玩星穹铁道");
        assertThat(fresh).isNotNull();
        Long oldId = jdbcTemplate.queryForObject(
                "SELECT id FROM memory_entry WHERE group_id = ? AND content = '张三喜欢原神'",
                Long.class, TEST_GROUP);
        MemoryEntryDO old = entryMapper.selectById(oldId);
        assertThat(old).isNotNull();
        assertThat(fresh).isNotNull();
        assertThat(old.getStatus()).isEqualTo(MemoryEntryDO.STATUS_SUPERSEDED);
        assertThat(old.getSupersededBy()).isEqualTo(fresh.getId());
        assertThat(fresh.getStatus()).isEqualTo(MemoryEntryDO.STATUS_ACTIVE);
    }

    @Test
    @Order(6)
    void 崩溃续跑_超时processing被兜底重投_不产生重复条目() {
        // 既有 failed 工单置终态（retry_count 拉满），防恢复 poll 后按 id 序抢走最老的 failed 工单
        jdbcTemplate.update("UPDATE sediment_job SET retry_count = 99 WHERE status = 'failed'");
        // compactOnce 的压缩摘要走 summarize 槽位真实调用，先入队一份文本剧本
        upstream.addScript("/chat/completions", List.of(sseTextChunk("本批无新内容。")));
        // 模拟 worker 在处理中崩溃：手动置 processing 且心跳超时（updated_at 拨到 1 小时前）
        SedimentJobDO job = compactOnce();
        jdbcTemplate.update("UPDATE sediment_job SET status = 'processing', "
                + "updated_at = DATE_SUB(NOW(3), INTERVAL 1 HOUR) WHERE id = " + job.getId());

        // 兜底扫描：超时重投回 pending
        scanner.resetStaleProcessing(java.time.LocalDateTime.now());

        // 恢复 poll 路径（claimBatch 恢复为 1——确定性消费该工单）
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"claimBatch\":1,\"processingTimeoutMinutes\":30,\"retryMax\":3,\"keepDoneDays\":30}'"
                        + " WHERE param_key = 'memory.sediment.worker'");
        systemParams.refresh();
        upstream.addScript("/chat/completions", List.of(
                extractionScript("{\"candidateEntries\":[],\"profileSignals\":[],\"conflicts\":[]}")));
        worker.poll();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobMapper.selectById(job.getId()).getStatus())
                        .isEqualTo(SedimentJobDO.STATUS_DONE));
        // 两级幂等：既有条目数不变（重复消费无重复行）
        long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = ?", Long.class, TEST_GROUP);
        assertThat(count).isEqualTo(3);
        // 恢复静默（后续用例的确定性）
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"claimBatch\":0,\"processingTimeoutMinutes\":30,\"retryMax\":3,\"keepDoneDays\":30}'"
                        + " WHERE param_key = 'memory.sediment.worker'");
        systemParams.refresh();
    }

    @Test
    @Order(7)
    void done行30天后清理_拨时钟到界外() {
        SedimentJobDO job = latestDoneJob();
        jdbcTemplate.update("UPDATE sediment_job SET updated_at = DATE_SUB(NOW(3), INTERVAL 31 DAY)"
                + " WHERE id = " + job.getId());

        int purged = scanner.cleanupDoneRows(java.time.LocalDateTime.now());

        assertThat(purged).isPositive();
        assertThat(jobMapper.selectById(job.getId())).isNull();
    }

    // =====================================================================
    // 生命周期全链路（4.2.7：强化 / 衰减 / 归档 / 取代撤销 / 总量控制）
    // =====================================================================

    @Test
    @Order(8)
    void 检索命中强化_单次加5_lastAccessed刷新() {
        MemoryEntryDO entry = findEntry("李四是程序员（群里常聊起）");
        jdbcTemplate.update("UPDATE memory_entry SET strength = 50 WHERE id = " + entry.getId());

        cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService lifecycle =
                applicationContext().getBean(cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService.class);
        lifecycle.reinforce(List.of(entry.getId()));

        MemoryEntryDO reinforced = entryMapper.selectById(entry.getId());
        assertThat(reinforced.getStrength()).isEqualTo(55);
        assertThat(reinforced.getLastAccessedAt()).isNotNull();
    }

    @Test
    @Order(9)
    void 每日衰减归档_老条目低于阈值置archived_新条目保持active() {
        // 制造一条 60 天前发生、30 天未访问的老条目（双时间轴 → 有效强度 0 → 归档）
        MemoryEntryDO template = findEntry("李四是程序员（群里常聊起）");
        MemoryEntryDO old = new MemoryEntryDO();
        old.setId(idGenerator.nextId());
        old.setPlatform("qq");
        old.setBotId("10000");
        old.setGroupId(TEST_GROUP);
        old.setMemoryType(MemoryEntryDO.TYPE_FACT);
        old.setContent("两个月前的旧事");
        old.setOccurredAt(java.time.LocalDateTime.now().minusDays(60));
        old.setLastAccessedAt(java.time.LocalDateTime.now().minusDays(30));
        old.setSedimentAt(java.time.LocalDateTime.now());
        old.setStrength(50);
        old.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        entryMapper.insertEntry(old);

        cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService lifecycle =
                applicationContext().getBean(cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService.class);
        lifecycle.runDailyDecayAndArchive();

        assertThat(entryMapper.selectById(old.getId()).getStatus())
                .isEqualTo(MemoryEntryDO.STATUS_ARCHIVED);
        // 新条目（昨天发生）保持 active
        assertThat(entryMapper.selectById(template.getId()).getStatus())
                .isEqualTo(MemoryEntryDO.STATUS_ACTIVE);
    }

    @Test
    @Order(10)
    void 归档可恢复_回active重新参与检索() {
        MemoryEntryDO archived = entryMapper.selectById(findArchivedId());
        jdbcTemplate.update("UPDATE memory_entry SET status = 'active' WHERE id = "
                + archived.getId());

        assertThat(entryMapper.selectById(archived.getId()).getStatus())
                .isEqualTo(MemoryEntryDO.STATUS_ACTIVE);
    }

    @Test
    @Order(11)
    void 软上限超群_加速最低强度衰减_无硬删负向断言() {
        // groupSoftLimit 拨低到 2（当前 active 3 条）触发加速
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"archiveThreshold\":15,\"archiveKeepDays\":180,\"groupSoftLimit\":2,\"dailyDecayRate\":1.0}'"
                        + " WHERE param_key = 'memory.decay.strength'");
        systemParams.refresh();
        Long countBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = ?", Long.class, TEST_GROUP);

        cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService lifecycle =
                applicationContext().getBean(cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService.class);
        lifecycle.runDailyDecayAndArchive();

        Long countAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM memory_entry WHERE group_id = ?", Long.class, TEST_GROUP);
        // 负向断言：加速遗忘永不硬删
        assertThat(countAfter).isEqualTo(countBefore);
    }

    @Test
    @Order(12)
    void 事务回滚_摘要与工单要么都在要么都不在() {
        long summariesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transcript_entry WHERE group_id = ? AND kind = 'summary'",
                Long.class, TEST_GROUP);
        long jobsBefore = jobMapper.selectByGroup("qq", "10000", TEST_GROUP).stream()
                .filter(job -> job.getStatus().equals(SedimentJobDO.STATUS_DONE)).count();

        appendMessages(40, "批次丁");
        // 回滚场景压缩摘要同样真实调用 summarize 槽位，先入队剧本（回滚后剧本不消费也不影响断言）
        upstream.addScript("/chat/completions", List.of(sseTextChunk("回滚批次的摘要。")));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        transaction.execute(status -> {
                            compactor.compactIfNeeded(session(), 30000, 8000);
                            throw new IllegalStateException("模拟压缩后失败回滚");
                        }))
                .hasMessageContaining("模拟压缩后失败回滚");

        // 摘要与工单都不在（回滚干净）
        long summariesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transcript_entry WHERE group_id = ? AND kind = 'summary'",
                Long.class, TEST_GROUP);
        long jobsAfter = jobMapper.selectByGroup("qq", "10000", TEST_GROUP).stream()
                .filter(job -> job.getStatus().equals(SedimentJobDO.STATUS_DONE)).count();
        assertThat(summariesAfter).isEqualTo(summariesBefore);
        assertThat(jobsAfter).isEqualTo(jobsBefore);
    }

    // =====================================================================
    // 断言与装配辅助
    // =====================================================================

    private org.springframework.context.ApplicationContext applicationContext() {
        return springContext;
    }

    @Autowired
    private org.springframework.context.ApplicationContext springContext;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private MemoryEntryDO findEntry(String content) {
        return entryMapper.selectActiveByGroup("qq", "10000", TEST_GROUP).stream()
                .filter(entry -> content.equals(entry.getContent()))
                .findFirst().orElse(null);
    }

    private long findArchivedId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM memory_entry WHERE group_id = ? AND status = 'archived' LIMIT 1",
                Long.class, TEST_GROUP);
    }

    private SedimentJobDO latestJob() {
        List<SedimentJobDO> jobs = jobMapper.selectByGroup("qq", "10000", TEST_GROUP);
        return jobs.get(jobs.size() - 1);
    }

    private SedimentJobDO latestDoneJob() {
        return jobMapper.selectByGroup("qq", "10000", TEST_GROUP).stream()
                .filter(job -> job.getStatus().equals(SedimentJobDO.STATUS_DONE))
                .reduce((first, second) -> second).orElseThrow();
    }

    private List<Long> qdrantSearchIds(String queryText) {
        return qdrantRestSearch(DeterministicEmbeddings.vectorOf(queryText, 8));
    }

    private JsonNode qdrantPointPayload(long pointId) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(qdrantBaseUrl() + "/collections/"
                                + MemoryIndexPoints.COLLECTION_ALIAS + "/points"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"ids\":[" + pointId + "],\"with_payload\":true}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode result = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared()
                .readTree(response.body()).path("result");
        return result.size() > 0 ? result.get(0).path("payload") : result;
    }

    /**
     * 检索断言走真 Qdrant REST（隔离三元组过滤强制注入的客户端侧同款形态）
     */
    private List<Long> qdrantRestSearch(float[] vector) {
        StringBuilder vectorJson = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                vectorJson.append(',');
            }
            vectorJson.append(vector[i]);
        }
        vectorJson.append(']');
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(qdrantBaseUrl() + "/collections/"
                                    + MemoryIndexPoints.COLLECTION_ALIAS + "/points/query"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"query\":{\"nearest\":" + vectorJson + "},\"limit\":10,"
                                            + "\"with_payload\":true,\"filter\":{\"must\":["
                                            + "{\"key\":\"platform\",\"match\":{\"value\":\"qq\"}},"
                                            + "{\"key\":\"botId\",\"match\":{\"value\":\"10000\"}},"
                                            + "{\"key\":\"groupId\",\"match\":{\"value\":\"" + TEST_GROUP + "\"}}]}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode points = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared()
                    .readTree(response.body()).path("result").path("points");
            List<Long> ids = new ArrayList<>();
            for (JsonNode point : points) {
                ids.add(point.path("id").asLong());
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 检索失败", e);
        }
    }

    private static void deleteQdrantCollection(String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(qdrantBaseUrl() + "/collections/" + name))
                .DELETE()
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private ProviderConfigDO providerRow() {
        ProviderConfigDO row = new ProviderConfigDO();
        row.setCode("zai-it");
        row.setDisplayName("zai-it");
        row.setDialect("openai-compat");
        row.setBaseUrl(upstream.baseUrl());
        row.setEnabled(true);
        return row;
    }

    private ModelConfigDO modelRow(String modelId, boolean summarize) {
        ModelConfigDO row = new ModelConfigDO();
        row.setProviderCode("zai-it");
        row.setModelId(modelId);
        row.setDisplayName(modelId);
        row.setCapability("{\"vision\":false,\"toolUse\":false,\"contextWindow\":8000,\"maxOutputTokens\":100}");
        row.setPrice("{\"input\":0.5,\"output\":0.5,\"cached\":0.1}");
        row.setEnabled(true);
        return row;
    }

    private ModelSlotDO slotRow(String slotCode, String modelId) {
        ModelSlotDO row = new ModelSlotDO();
        row.setSlotCode(slotCode);
        row.setProviderCode("zai-it");
        row.setModelId(modelId);
        row.setGenParams("{\"temperature\":0.3,\"maxOutputTokens\":512}");
        row.setEnabled(true);
        return row;
    }
}
