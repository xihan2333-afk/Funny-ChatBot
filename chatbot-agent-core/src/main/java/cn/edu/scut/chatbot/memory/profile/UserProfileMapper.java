package cn.edu.scut.chatbot.memory.profile;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * user_profile 表 Mapper：首互动自动建行（INSERT ON DUPLICATE KEY——uk 拒插后并轨更新）、
 * 态度读（当前值 + 懒衰减基准）、事件微调、沉淀统计刷新、个体清除。
 */
@Mapper
public interface UserProfileMapper {

    /**
     * 建行或并轨更新（首互动自动建行 + 沉淀画像增量的同一入口）
     */
    int upsertProfile(UserProfileDO profile);

    UserProfileDO selectByGroupAndUser(@Param("platform") String platform,
                                       @Param("botId") String botId,
                                       @Param("groupId") String groupId,
                                       @Param("userId") String userId);

    List<UserProfileDO> selectByGroup(@Param("platform") String platform,
                                      @Param("botId") String botId,
                                      @Param("groupId") String groupId);

    /**
     * 事件微调与统计增量（familiarity 与 affinity 的一次原子累计）
     */
    int applyDeltas(@Param("platform") String platform,
                    @Param("botId") String botId,
                    @Param("groupId") String groupId,
                    @Param("userId") String userId,
                    @Param("familiarityDelta") int familiarityDelta,
                    @Param("affinityDelta") int affinityDelta,
                    @Param("platformName") String platformName);

    /**
     * 画像字段整体刷新（称呼 / 兴趣标签 / 互动摘要 / 活跃度分档——统计批任务用）
     */
    int updateFields(@Param("id") long id,
                     @Param("callName") String callName,
                     @Param("interestTags") String interestTags,
                     @Param("interactDigest") String interactDigest,
                     @Param("activityLevel") String activityLevel,
                     @Param("affinity") Integer affinity);

    /**
     * 个体数据清除（群 + userId 的画像行删除）
     */
    int deleteByGroupAndUser(@Param("platform") String platform,
                             @Param("botId") String botId,
                             @Param("groupId") String groupId,
                             @Param("userId") String userId);
}
