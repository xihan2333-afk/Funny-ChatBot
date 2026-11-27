package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * group_config 表 Mapper。
 */
@Mapper
public interface GroupConfigMapper {

    GroupConfigDO selectByGroup(@Param("platform") String platform,
                                @Param("botId") String botId,
                                @Param("groupId") String groupId);

    /**
     * 幂等 UPSERT：群发现时同步建配置行（黑名单与授权清单给安全默认值——空黑名单、
     * 不允许任何 member/autonomous 类别：宁严勿松）
     */
    int upsertDefault(@Param("platform") String platform,
                      @Param("botId") String botId,
                      @Param("groupId") String groupId);

    /**
     * 黑名单更新（策略可运营：管理面 / SQL 维护）
     */
    int updateUserBlacklist(@Param("platform") String platform,
                            @Param("botId") String botId,
                            @Param("groupId") String groupId,
                            @Param("userBlacklist") String userBlacklistJson);

    /**
     * 授权清单更新（策略变更触发 Mandate 重验证）
     */
    int updateAllowedCategories(@Param("platform") String platform,
                                @Param("botId") String botId,
                                @Param("groupId") String groupId,
                                @Param("allowedMemberTaskCategories") String allowedMemberJson,
                                @Param("allowedAutonomousCategories") String allowedAutonomousJson);

    /**
     * 更新群全量六块配置
     */
    int updateFullConfig(GroupConfigDO config);

    /**
     * 更新销毁时会话历史沉淀策略（keep / discard）
     */
    int updateTranscriptRetainPolicy(@Param("platform") String platform,
                                     @Param("botId") String botId,
                                     @Param("groupId") String groupId,
                                     @Param("policy") String policy);
}
