package cn.edu.scut.chatbot.memory.retrieve;

/**
 * 维度绑定防护（contracts/memory-retrieval.md §4）：embedding 槽位换绑模型时，
 * 维度与集合登记不一致必须阻断——不静默生效（静默 = 检索静默劣化，比失败更糟）。
 */
public interface CollectionGuard {

    /**
     * 启动自检：集合不存在则按当前 embedding 槽位实际维度创建并回填登记
     * （system_param memory.qdrant.collection_meta）；存在则校验登记维度与实际
     * 维度一致（双源校验，不一致告警）。embedding 槽位未就绪时推迟创建（告警）。
     */
    void ensureCollection();

    /**
     * 槽位换绑校验：新模型维度 ≠ 集合登记维度 → 抛阻断异常（提示需执行迁移），不静默生效。
     * 附带供应商校验：DeepSeek 不提供 embedding API，embedding 槽位只接受
     * 智谱 / 豆包 / OpenAI / Gemini。
     */
    void validateEmbeddingModelChange(String providerCode, String newModelCode, int newDim);

    /**
     * 重嵌入迁移任务：新建 memory_entry_v{n} 集合 → 全量重灌 → 切别名（零停机），
     * 登记更新为新维度。运营操作门面。
     */
    MemoryRetriever.RebuildReport migrateToNewDimension();
}
