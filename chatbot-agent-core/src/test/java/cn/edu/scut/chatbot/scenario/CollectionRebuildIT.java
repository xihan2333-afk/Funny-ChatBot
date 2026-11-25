// 守护纪律：Qdrant 是派生索引可全量重建（MySQL 是事实源——删光索引可恢复）、
// switchAlias 零停机切换（切换前走旧集合、切换后走新集合无空窗）、
// 换绑新维度被阻断不静默生效（quickstart 场景 C；contracts/memory-retrieval.md §4）
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.fixtures.DeterministicEmbeddings;
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.slot.*;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.retrieve.CollectionGuard;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重建演练 IT（quickstart 场景 C）：真 MySQL + 真 Qdrant + 确定性伪向量假上游——
 * 清空索引后从 MySQL 全量重灌、检索恢复且业务字段无损；重嵌入迁移换维度集合
 * v2 重灌 + switchAlias 零停机；换绑新维度被阻断。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class CollectionRebuildIT extends AbstractIntegrationTest {

    private static final String TEST_GROUP = "94001";

    /**
     * 当前假 embedding 维度（volatile：迁移演练中途切 16 模拟换绑更高维模型）
     */
    private static volatile int embeddingDim = 8;

    /**
     * 文本 → 向量覆盖表（近邻可构造；本 IT 用哈希派生即可）
     */
    private static final Map<String, float[]> VECTOR_OVERRIDES = new ConcurrentHashMap<>();

    private static ScriptedHttpUpstream upstream;

    @Autowired
    private MemoryEntryMapper entryMapper;

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private MemoryRetriever retriever;

    @Autowired
    private CollectionGuard guard;

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

    @BeforeAll
    void seed() throws Exception {
        upstream = new ScriptedHttpUpstream();
        upstream.addDynamicScript("/embeddings", request ->
                DeterministicEmbeddings.embeddingsResponseFor(request, embeddingDim, VECTOR_OVERRIDES));
        upstream.start();

        // 容器是测试类间共享单例——清残留别名/物理集合与登记，回到「首启」状态
        deleteAlias("memory_entry");
        deleteCollection("memory_entry");
        deleteCollection("memory_entry_v1");
        deleteCollection("memory_entry_v2");
        jdbcTemplate.update("DELETE FROM memory_entry");
        jdbcTemplate.update(
                "UPDATE system_param SET param_value = '{\"collection\":\"memory_entry\",\"dim\":null,\"embedModelCode\":null}'"
                        + " WHERE param_key = 'memory.qdrant.collection_meta'");
        jdbcTemplate.update("DELETE FROM model_slot WHERE slot_code = 'embedding'");
        jdbcTemplate.update("DELETE FROM model_config WHERE model_id = 'embedding-it'");
        jdbcTemplate.update("DELETE FROM provider_config WHERE code = 'zai-it'");
        jdbcTemplate.update("DELETE FROM credential WHERE owner_kind = 'provider' AND owner_code = 'zai-it'");

        providerMapper.insert(providerRow());
        modelMapper.insert(modelRow());
        slotMapper.insert(slotRow());

        CredentialCodec codec = CredentialCodec.fromBase64Key(
                Base64.getEncoder().encodeToString(new byte[32]));
        CredentialDO credential = new CredentialDO();
        credential.setOwnerKind("provider");
        credential.setOwnerCode("zai-it");
        credential.setKeyCipher(codec.encryptToBytes("sk-rebuild-0001"));
        credential.setKeyMask(CredentialPool.maskOf("sk-rebuild-0001"));
        credential.setStatus("available");
        credentialMapper.insertCredential(credential);
        catalog.refresh();

        // 首启自检：集合不存在 → 建物理 memory_entry_v1（探测维度 8）+ 别名 memory_entry → v1
        guard.ensureCollection();
        QdrantClient.QdrantCollectionInfo info = qdrantClient.describeCollection("memory_entry");
        assertThat(info).isNotNull();
        assertThat(info.dim()).isEqualTo(8);
        QdrantClient.QdrantCollectionInfo physicalV1 =
                qdrantClient.describeCollection("memory_entry_v1");
        assertThat(physicalV1).isNotNull();

        // MySQL 事实源就位（固定 id 9001~9003；强度各不同——重排因子素材）
        insertEntry(9001L, "李四是程序员", MemoryEntryDO.TYPE_FACT, null, 50);
        insertEntry(9002L, "张三喜欢原神", MemoryEntryDO.TYPE_PREFERENCE, "u1", 80);
        insertEntry(9003L, "群里在聊装修预算", MemoryEntryDO.TYPE_TOPIC, null, 30);

        // 初始灌入（工单管线属 US2——重建场景直接以 rebuild 起步）
        retriever.rebuildCollection("memory_entry", false);
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    @Order(1)
    void 清空索引后从MySQL全量重灌_检索恢复且业务字段无损() {
        // 清空 Qdrant 点（模拟索引灾难性丢失——事实源在 MySQL）
        qdrantClient.delete(MemoryIndexPoints.COLLECTION_ALIAS, List.of(9001L, 9002L, 9003L));
        assertThat(searchIds("李四是程序员", 8)).isEmpty();

        MemoryRetriever.RebuildReport report =
                retriever.rebuildCollection(MemoryIndexPoints.COLLECTION_ALIAS, false);

        assertThat(report.totalEntries()).isEqualTo(3);
        assertThat(report.aliasSwitched()).isFalse();
        // 哈希伪向量下远邻也会被召回（低相似非零）——同文本向量与自身余弦为 1，
        // 断言锚定 top-1 命中目标条目
        assertThat(searchIds("李四是程序员", 8)).first().isEqualTo(9001L);

        // 业务字段以 MySQL 为准——重灌不动事实源
        MemoryEntryDO entry = entryMapper.selectById(9001L);
        assertThat(entry.getStrength()).isEqualTo(50);
        assertThat(entry.getContent()).isEqualTo("李四是程序员");
    }

    @Test
    @Order(2)
    void 检索命中payload只含稳定过滤字段_易变字段不进索引() {
        List<QdrantClient.QdrantHit> hits = searchHits("李四是程序员", 8);

        assertThat(hits).isNotEmpty();
        Map<String, Object> payload = hits.get(0).payload();
        assertThat(payload).containsEntry("groupId", TEST_GROUP);
        assertThat(payload).containsEntry("memoryType", MemoryEntryDO.TYPE_FACT);
        assertThat(payload).containsKey("occurredAt");
        assertThat(payload).doesNotContainKeys("strength", "lastAccessedAt", "status", "topicLifecycle");
    }

    @Test
    @Order(3)
    void 换绑新维度被阻断_不静默生效() {
        // 模拟槽位换绑 8 维 → 16 维（假上游从此返回 16 维向量）
        embeddingDim = 16;

        assertThatThrownBy(() -> guard.validateEmbeddingModelChange("zai-it", "embedding-it-v2", 16))
                .isInstanceOf(cn.edu.scut.chatbot.exception.NonRetryableException.class)
                .hasMessageContaining("16")
                .hasMessageContaining("8")
                .hasMessageContaining("迁移");
    }

    @Test
    @Order(4)
    void 重嵌入迁移_新维度集合重灌_switchAlias零停机切换() {
        // 切换前：别名走旧集合（8 维查询仍命中——零停机的「前」半段）
        assertThat(searchIds("李四是程序员", 8)).first().isEqualTo(9001L);

        MemoryRetriever.RebuildReport report = guard.migrateToNewDimension();

        assertThat(report.aliasSwitched()).isTrue();
        assertThat(report.targetCollection()).isEqualTo("memory_entry_v2");
        assertThat(report.totalEntries()).isEqualTo(3);

        // 切换后：别名走新集合（16 维查询命中、无空窗）
        QdrantClient.QdrantCollectionInfo physical =
                qdrantClient.describeCollection("memory_entry_v2");
        assertThat(physical).isNotNull();
        assertThat(physical.dim()).isEqualTo(16);
        assertThat(searchIds("李四是程序员", 16)).first().isEqualTo(9001L);
        assertThat(searchIds("张三喜欢原神", 16)).first().isEqualTo(9002L);

        // 登记更新为新维度（后续换绑校验以 16 为基准）
        String meta = jdbcTemplate.queryForObject(
                "SELECT param_value FROM system_param WHERE param_key = 'memory.qdrant.collection_meta'",
                String.class);
        assertThat(meta).contains("\"dim\":16");
    }

    @Test
    @Order(5)
    void 迁移后再次换绑同维度不再阻断() {
        guard.validateEmbeddingModelChange("zai-it", "embedding-it-v3", 16);
    }

    // ===== 断言与夹具 =====

    private List<Long> searchIds(String queryText, int dim) {
        return searchHits(queryText, dim).stream().map(QdrantClient.QdrantHit::id).toList();
    }

    private List<QdrantClient.QdrantHit> searchHits(String queryText, int dim) {
        return qdrantClient.search(MemoryIndexPoints.COLLECTION_ALIAS,
                DeterministicEmbeddings.vectorOf(queryText, dim),
                groupFilter(), 10);
    }

    private QdrantClient.QdrantFilter groupFilter() {
        return new QdrantClient.QdrantFilter(
                List.of(new QdrantClient.QdrantFilter.KeywordCondition("platform", "qq"),
                        new QdrantClient.QdrantFilter.KeywordCondition("botId", "10000"),
                        new QdrantClient.QdrantFilter.KeywordCondition("groupId", TEST_GROUP)),
                List.of());
    }

    private void insertEntry(long id, String content, String memoryType, String userId, int strength) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setPlatform("qq");
        entry.setBotId("10000");
        entry.setGroupId(TEST_GROUP);
        entry.setMemoryType(memoryType);
        entry.setContent(content);
        entry.setUserId(userId);
        entry.setOccurredAt(LocalDateTime.of(2026, 11, 20, 12, 0));
        entry.setSedimentAt(LocalDateTime.of(2026, 11, 21, 8, 0));
        entry.setStrength(strength);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        entryMapper.insertEntry(entry);
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

    private ModelConfigDO modelRow() {
        ModelConfigDO row = new ModelConfigDO();
        row.setProviderCode("zai-it");
        row.setModelId("embedding-it");
        row.setDisplayName("embedding-it");
        row.setCapability("{\"vision\":false,\"toolUse\":false,\"contextWindow\":8000,\"maxOutputTokens\":100}");
        row.setPrice("{\"input\":0.5,\"output\":0.5,\"cached\":0.1}");
        row.setEnabled(true);
        return row;
    }

    private ModelSlotDO slotRow() {
        ModelSlotDO row = new ModelSlotDO();
        row.setSlotCode(ModelCatalog.SLOT_EMBEDDING);
        row.setProviderCode("zai-it");
        row.setModelId("embedding-it");
        row.setGenParams("{\"temperature\":0.2,\"maxOutputTokens\":100}");
        row.setEnabled(true);
        return row;
    }

    private static void deleteCollection(String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(qdrantBaseUrl() + "/collections/" + name))
                .DELETE()
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void deleteAlias(String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(qdrantBaseUrl() + "/collections/aliases"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"actions\":[{\"delete_alias\":{\"alias_name\":\"" + name + "\"}}]}"))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
