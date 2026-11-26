package cn.edu.scut.chatbot.memory.entry;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * memory_entry 表 Mapper：检索现读（按点 id 批量）、重建全量游标（selectActiveAfterId）、
 * 强化批量一次 UPDATE、衰减归档与取代链、话题生命周期（US8）、软上限加速候选。
 */
@Mapper
public interface MemoryEntryMapper {

    int insertEntry(MemoryEntryDO entry);

    MemoryEntryDO selectById(@Param("id") long id);

    /**
     * 按点 id 批量现读（检索管道第三步：正文 / strength / lastAccessedAt 不进 Qdrant）
     */
    List<MemoryEntryDO> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 按群统计 active 条数（软上限总量控制的判定输入）
     */
    long countActiveByGroup(@Param("platform") String platform,
                            @Param("botId") String botId,
                            @Param("groupId") String groupId);

    /**
     * 单群全部 active 条目（衰减批任务与重建的分群形态）
     */
    List<MemoryEntryDO> selectActiveByGroup(@Param("platform") String platform,
                                            @Param("botId") String botId,
                                            @Param("groupId") String groupId);

    /**
     * 全库 active 游标分批（重建重灌全量：id 递增游标避免深分页）
     */
    List<MemoryEntryDO> selectActiveAfterId(@Param("afterId") long afterId,
                                            @Param("limit") int limit);

    /**
     * 单群强度最低的 active 条目（软上限加速衰减——先忘不重要的）
     */
    List<MemoryEntryDO> selectLowestStrengthActive(@Param("platform") String platform,
                                                   @Param("botId") String botId,
                                                   @Param("groupId") String groupId,
                                                   @Param("limit") int limit);

    /**
     * 强化批量一次 UPDATE：strength += boost（上限 100 钳制）、lastAccessedAt = now
     */
    int reinforceBatch(@Param("ids") List<Long> ids,
                       @Param("boost") int boost,
                       @Param("now") LocalDateTime now);

    /**
     * 衰减应用与归档判定（写侧批任务逐行应用计算值）
     */
    int updateStrengthAndStatus(@Param("id") long id,
                                @Param("strength") int strength,
                                @Param("status") String status,
                                @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 归档满保留期物理清理（updated_at 即归档时刻）
     */
    int deleteArchivedBefore(@Param("cutoff") LocalDateTime cutoff,
                             @Param("limit") int limit);

    /**
     * 冲突取代：旧条置 superseded、superseded_by 指向新条（互链留痕）
     */
    int supersede(@Param("oldId") long oldId, @Param("newId") long newId);

    /**
     * 撤销取代：回 active（管理面恢复）
     */
    int revokeSupersede(@Param("oldId") long oldId);

    /**
     * 修正正文（管理面修正派生动作——改后由 MemoryRetriever.reembed 重嵌入）
     */
    int updateContent(@Param("id") long id, @Param("content") String content);

    /**
     * 物理删除（两库同删的 MySQL 侧；归档清理同用）
     */
    int deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 话题生命周期迁移（hot / cooling / closed——US8）
     */
    int updateTopicLifecycle(@Param("id") long id, @Param("lifecycle") String lifecycle);

    /**
     * 单群指定生命周期的话题条目（冷却判定与再热唤醒扫描）
     */
    List<MemoryEntryDO> selectTopicByLifecycle(@Param("platform") String platform,
                                               @Param("botId") String botId,
                                               @Param("groupId") String groupId,
                                               @Param("lifecycle") String lifecycle);

    /**
     * 有 active 条目的群清单（衰减批任务分群遍历）
     */
    List<MemoryGroupDO> selectActiveGroups();

    /**
     * 窗口内沉淀量计数（社交报表：sediment_at 落在窗口内的条数）
     */
    long countSedimentBetween(@Param("from") java.time.LocalDateTime from,
                              @Param("to") java.time.LocalDateTime to,
                              @Param("groupId") String groupId);
}
