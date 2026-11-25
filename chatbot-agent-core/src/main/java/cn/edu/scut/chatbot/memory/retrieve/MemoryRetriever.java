package cn.edu.scut.chatbot.memory.retrieve;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

import java.util.List;

/**
 * 记忆检索接口（contracts/memory-retrieval.md §2）：searchMemory 工具的生产内核，
 * 以及记忆修正派生的三个管理动作（重嵌入 / 索引删除 / 全量重建）。
 */
public interface MemoryRetriever {

    /**
     * 六步检索管道（T023 生产实现）：查询向量化 → Qdrant search（管道强制注入隔离
     * 三元组过滤）→ 按点 id 现读 MySQL → 应用层重排 → 截取裁剪 → 定界包裹返回。
     */
    MemorySearchResult search(GroupKey group, MemorySearchQuery query);

    /**
     * 记忆修正后的重嵌入（正文变更 → 重新 embed → Qdrant 同 id upsert）
     */
    void reembed(GroupKey group, long memoryId);

    /**
     * 记忆删除 / 归档 / 恢复的 Qdrant 同步（删除两库同删；归档退出检索、恢复重建）
     */
    void deleteFromIndex(List<Long> memoryIds);

    /**
     * 重建：从 MySQL 全量 active 条目重灌目标集合（Qdrant 删光后可从事实源恢复——
     * 派生索引的既定语义）；switchAliasAfter=true 时灌完切别名（迁移零停机）
     */
    RebuildReport rebuildCollection(String targetCollection, boolean switchAliasAfter);

    /**
     * 检索入参（queryText 必填；其余可空 = 不过滤）
     */
    record MemorySearchQuery(String queryText, String memoryType, String userId, Integer withinDays) {
    }

    /**
     * 检索产出：error=true 走工具 isError（不杀循环）；无结果是正常业务文本非 error
     */
    record MemorySearchResult(boolean error, String content) {
    }

    /**
     * 重建报告（迁移与重建演练的观测面）
     */
    record RebuildReport(int totalEntries, int upsertedPoints,
                         String targetCollection, boolean aliasSwitched) {
    }
}
