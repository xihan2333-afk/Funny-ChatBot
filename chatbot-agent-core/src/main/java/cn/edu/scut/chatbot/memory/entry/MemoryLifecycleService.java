package cn.edu.scut.chatbot.memory.entry;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

import java.util.List;

/**
 * 记忆生命周期服务契约（contracts/sediment-pipeline.md §5）：
 * 强化 / 遗忘衰减与归档 / 冲突取代 / 修正派生动作。
 */
public interface MemoryLifecycleService {

    /**
     * 强化：检索命中进入返回结果时调用。strength += N（单次上限、连续命中不突破）、
     * lastAccessedAt = now，批量一次 UPDATE
     */
    void reinforce(List<Long> memoryIds);

    /**
     * 每日批任务：应用衰减值、归档判定（衰减到阈值 → archived 不再参与检索可恢复）、
     * 归档满 archiveKeepDays 物理清理、每群超软上限加速最低强度条目衰减（不硬删）。
     * 分群分批限量（避免长事务）
     */
    void runDailyDecayAndArchive();

    /**
     * 冲突取代：旧条 superseded + superseded_by 指向新条互链
     */
    void supersede(long oldId, long newId);

    /**
     * 撤销取代：回 active（管理面恢复）
     */
    void revokeSupersede(long oldId);

    /**
     * 管理面修正：改正文 → 自动重嵌入（同 id upsert）
     */
    void reviseContent(GroupKey group, long memoryId, String newContent);

    /**
     * 删除：两库同删
     */
    void deleteMemory(GroupKey group, long memoryId);

    /**
     * 个体数据清除（群 + userId）：该成员的记忆与画像按规清除并同步 Qdrant 删除
     * （转录的清除归 engine 侧 2.7 管理面编排——本门面只清认知域数据）
     */
    void purgeMemberData(GroupKey group, String userId);
}
