package cn.edu.scut.chatbot.memory.retrieve;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamMapper;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.SlotResolver;
import cn.edu.scut.chatbot.loop.ModelRef;
import cn.edu.scut.chatbot.memory.MemoryErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 维度绑定防护实现（research R3）：集合元数据登记在 system_param
 * （memory.qdrant.collection_meta：collection / dim / embedModelCode），与
 * describeCollection 的实际维度构成双源——Qdrant 无任意元数据端点，登记必须落在本地。
 */
@Component
public class CollectionGuardImpl implements CollectionGuard {

    private static final Logger log = LoggerFactory.getLogger(CollectionGuardImpl.class);

    /**
     * 集合元数据 system_param 键（V7 种子预置空登记，首启自检回填）
     */
    static final String META_PARAM_KEY = "memory.qdrant.collection_meta";

    /**
     * embedding 槽位（维度探测与登记的模型来源）
     */
    private static final String EMBEDDING_SLOT = ModelCatalog.SLOT_EMBEDDING;

    /**
     * 维度探测文本（内容无关——只取返回向量长度）
     */
    private static final String DIM_PROBE_TEXT = "dimension-probe";

    /**
     * 首版物理集合版本号（别名 memory_entry → memory_entry_v1 起步；
     * Qdrant 不允许别名与物理集合同名，主名永远让给别名）
     */
    private static final int FIRST_PHYSICAL_VERSION = 1;

    /**
     * 迁移物理集合版本起点
     */
    private static final int FIRST_MIGRATION_VERSION = 2;

    private final QdrantClient qdrant;
    private final SlotResolver slotResolver;
    private final EmbeddingClient embeddingClient;
    private final SystemParamService systemParams;
    private final SystemParamMapper systemParamMapper;
    private final MemoryRetriever retriever;

    public CollectionGuardImpl(QdrantClient qdrant,
                               SlotResolver slotResolver,
                               EmbeddingClient embeddingClient,
                               SystemParamService systemParams,
                               SystemParamMapper systemParamMapper,
                               MemoryRetriever retriever) {
        this.qdrant = qdrant;
        this.slotResolver = slotResolver;
        this.embeddingClient = embeddingClient;
        this.systemParams = systemParams;
        this.systemParamMapper = systemParamMapper;
        this.retriever = retriever;
    }

    @Override
    public void ensureCollection() {
        CollectionMeta meta = readMeta();
        QdrantClient.QdrantCollectionInfo info = qdrant.describeCollection(meta.collection());
        if (info == null) {
            ModelRef slot = currentEmbeddingSlotOrNull();
            Integer dim = meta.dim() != null ? meta.dim() : (slot != null ? probeDimOrNull() : null);
            if (dim == null) {
                log.warn("Qdrant 集合未创建且 embedding 槽位不可探测（未绑定或无凭证），"
                        + "集合创建推迟——槽位就绪后重启或手动触发自检 collection={}", meta.collection());
                return;
            }
            String modelCode = meta.embedModelCode() != null ? meta.embedModelCode()
                    : (slot != null ? slot.modelId() : null);
            // 物理集合带版本号、主名让给别名（Qdrant 拒绝别名与物理集合同名）：
            // 首版 memory_entry_v1 + 别名 memory_entry → v1，迁移时 v2 换别名零停机
            String physical = meta.collection() + "_v" + FIRST_PHYSICAL_VERSION;
            if (qdrant.describeCollection(physical) == null) {
                qdrant.createCollection(physical, dim, MemoryIndexPoints.STABLE_PAYLOAD_INDEXES);
            }
            qdrant.switchAlias(meta.collection(), physical);
            writeMeta(meta.collection(), dim, modelCode);
            log.info("Qdrant 集合创建并登记维度 alias={} physical={} dim={} modelCode={}",
                    meta.collection(), physical, dim, modelCode);
            return;
        }
        if (meta.dim() == null) {
            writeMeta(meta.collection(), info.dim(), meta.embedModelCode());
            log.info("集合维度登记回填 collection={} dim={}（实际维度来自 describeCollection）",
                    meta.collection(), info.dim());
            return;
        }
        if (meta.dim() != info.dim()) {
            log.error("Qdrant 集合维度与登记不一致 errorCode={} collection={} 登记维度={} 实际维度={}"
                            + "——向量写入将持续失败，需执行重嵌入迁移（migrateToNewDimension）",
                    MemoryErrorCodes.QDRANT_DIM_MISMATCH, meta.collection(), meta.dim(), info.dim());
        }
    }

    @Override
    public void validateEmbeddingModelChange(String providerCode, String newModelCode, int newDim) {
        if (isRejectedProvider(providerCode)) {
            throw new NonRetryableException(MemoryErrorCodes.QDRANT_PROVIDER_REJECTED,
                    "供应商 " + providerCode + " 不提供 embedding API（DeepSeek 无向量化端点），"
                            + "embedding 槽位只接受智谱 / 豆包 / OpenAI / Gemini");
        }
        CollectionMeta meta = readMeta();
        if (meta.dim() != null && meta.dim() != newDim) {
            throw new NonRetryableException(MemoryErrorCodes.QDRANT_DIM_MISMATCH,
                    "新模型 " + newModelCode + " 声明维度 " + newDim + " ≠ 集合登记维度 " + meta.dim()
                            + "——需先执行重嵌入迁移（migrateToNewDimension），不会静默生效");
        }
    }

    @Override
    public MemoryRetriever.RebuildReport migrateToNewDimension() {
        ModelRef slot = currentEmbeddingSlot();
        int newDim = probeDim();
        CollectionMeta meta = readMeta();
        if (meta.dim() != null && meta.dim() == newDim) {
            log.info("当前 embedding 槽位维度与登记一致（dim={}），无需迁移", newDim);
            return new MemoryRetriever.RebuildReport(0, 0, meta.collection(), false);
        }
        String target = nextVersionCollectionName(meta);
        log.info("重嵌入迁移开始 {} → {}（dim {} → {}）", meta.collection(), target, meta.dim(), newDim);
        MemoryRetriever.RebuildReport report = retriever.rebuildCollection(target, true);
        writeMeta(meta.collection(), newDim, slot.modelId());
        log.info("重嵌入迁移完成 total={} aliasSwitched={} 登记维度更新为 {}",
                report.totalEntries(), report.aliasSwitched(), newDim);
        return report;
    }

    // ===== 登记读写与探测 =====

    /**
     * 登记形态（system_param JSON）：collection 是别名（迁移换物理集合不换名）
     */
    record CollectionMeta(String collection, Integer dim, String embedModelCode) {

        static CollectionMeta empty() {
            return new CollectionMeta(MemoryIndexPoints.COLLECTION_ALIAS, null, null);
        }
    }

    CollectionMeta readMeta() {
        String raw = systemParams.getString(META_PARAM_KEY, null);
        if (raw == null || raw.isBlank()) {
            return CollectionMeta.empty();
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(raw);
            JsonNode dim = node.path("dim");
            String modelCode = node.path("embedModelCode").asText(null);
            if (modelCode != null && modelCode.isBlank()) {
                modelCode = null;
            }
            String collection = node.path("collection").asText("");
            return new CollectionMeta(
                    collection.isBlank() ? MemoryIndexPoints.COLLECTION_ALIAS : collection,
                    dim.isNumber() ? dim.asInt() : null,
                    modelCode);
        } catch (Exception e) {
            log.warn("collection_meta 登记解析失败按空登记处理", e);
            return CollectionMeta.empty();
        }
    }

    void writeMeta(String collection, Integer dim, String embedModelCode) {
        com.fasterxml.jackson.databind.node.ObjectNode json =
                JsonMappers.shared().createObjectNode();
        json.put("collection", collection);
        if (dim == null) {
            json.putNull("dim");
        } else {
            json.put("dim", dim);
        }
        if (embedModelCode == null) {
            json.putNull("embedModelCode");
        } else {
            json.put("embedModelCode", embedModelCode);
        }
        cn.edu.scut.chatbot.bootstrap.SystemParamDO param = new cn.edu.scut.chatbot.bootstrap.SystemParamDO();
        param.setParamKey(META_PARAM_KEY);
        param.setParamValue(json.toString());
        param.setDescription("Qdrant 集合维度登记（首启自检回填；换绑双源校验）");
        systemParamMapper.upsertParam(param);
        systemParams.refresh();
    }

    private ModelRef currentEmbeddingSlot() {
        try {
            return slotResolver.resolve(EMBEDDING_SLOT).model();
        } catch (ModelCatalog.SlotResolutionException e) {
            throw new NonRetryableException(MemoryErrorCodes.QDRANT_SLOT_UNBOUND,
                    "embedding 槽位未绑定，无法探测维度：" + e.getMessage());
        }
    }

    private ModelRef currentEmbeddingSlotOrNull() {
        try {
            return slotResolver.resolve(EMBEDDING_SLOT).model();
        } catch (ModelCatalog.SlotResolutionException e) {
            return null;
        }
    }

    private int probeDim() {
        ModelRef slot = currentEmbeddingSlot();
        List<float[]> vectors = embeddingClient.embed(EMBEDDING_SLOT,
                LlmCallMeta.of("embedding", "dim-probe"), List.of(DIM_PROBE_TEXT));
        if (vectors.isEmpty() || vectors.get(0).length == 0) {
            throw new NonRetryableException(MemoryErrorCodes.QDRANT_SLOT_UNBOUND,
                    "embedding 槽位探测返回空向量（model=" + slot.modelId() + "）");
        }
        return vectors.get(0).length;
    }

    private Integer probeDimOrNull() {
        try {
            return probeDim();
        } catch (RuntimeException e) {
            log.warn("embedding 维度探测失败（集合创建推迟）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * DeepSeek 无 embedding API（设计 2.4.2 运营注意）——供应商码可辨即拒。
     * 其余供应商放行（绑定界面语义校验归管理平台 2.7，本处是数据面兜底）
     */
    private static boolean isRejectedProvider(String providerCode) {
        return providerCode != null && providerCode.toLowerCase().contains("deepseek");
    }

    /**
     * 下一个迁移物理集合名：memory_entry_v2、v3…（已存在的版本号跳过）
     */
    private String nextVersionCollectionName(CollectionMeta meta) {
        int version = FIRST_MIGRATION_VERSION;
        while (qdrant.describeCollection(meta.collection() + "_v" + version) != null) {
            version++;
        }
        return meta.collection() + "_v" + version;
    }
}
