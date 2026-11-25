package cn.edu.scut.chatbot.memory;

/**
 * memory 域错误码（点分短码，风格与 EngineErrorCodes / LlmErrorCodes 一致）：
 * 只描述「系统哪里坏了」，不承载业务分支；业务拒绝走 CommandRejectedException
 * 或工具 isError 结果。Qdrant 客户端层的基础码在
 * {@link cn.edu.scut.chatbot.infrastructure.qdrant.QdrantErrorCodes}。
 */
public final class MemoryErrorCodes {

    private MemoryErrorCodes() {
    }

    /**
     * 换绑维度与集合登记不符——阻断（需执行重嵌入迁移，不静默生效）
     */
    public static final String QDRANT_DIM_MISMATCH = "memory.qdrant.dim_mismatch";

    /**
     * embedding 槽位绑定的供应商不提供 embedding API（DeepSeek 等）——阻断
     */
    public static final String QDRANT_PROVIDER_REJECTED = "memory.qdrant.provider_rejected";

    /**
     * 需要向量化但 embedding 槽位未绑定（迁移/重建场景是硬前提）
     */
    public static final String QDRANT_SLOT_UNBOUND = "memory.qdrant.slot_unbound";

    /**
     * 沉淀抽取输出不可解析（跳批告警、工单 failed 由兜底重投——非崩溃路径）
     */
    public static final String SEDIMENT_EXTRACTION_INVALID = "memory.sediment.extraction_invalid";

    /**
     * 人格演进提案生成失败（周期任务告警下期重试、人格不受影响——非崩溃路径）
     */
    public static final String EVOLUTION_PROPOSAL_INVALID = "memory.evolution.proposal_invalid";
}
