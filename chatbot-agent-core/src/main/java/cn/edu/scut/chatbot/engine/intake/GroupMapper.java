package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * group 表 Mapper：discoverGroup 幂等 UPSERT（lifecycle=DISCOVERED）、in_service 判定查询。
 */
@Mapper
public interface GroupMapper {

    /**
     * 幂等 UPSERT：首个见到 groupId 的信封建行（lifecycle=DISCOVERED、in_service=0）；
     * 已存在则刷新群名（信封可携带时），不动 lifecycle 与 in_service（运营决策）
     */
    int upsertDiscovered(@Param("platform") String platform,
                         @Param("botId") String botId,
                         @Param("groupId") String groupId,
                         @Param("groupName") String groupName);

    /**
     * 按三元组取群行
     */
    GroupDO selectByGroup(@Param("platform") String platform,
                          @Param("botId") String botId,
                          @Param("groupId") String groupId);

    /**
     * 全部服务中的群（调度器状态扫描：in_service=1 且 ACTIVE）
     */
    java.util.List<GroupDO> selectAllInService();

    /**
     * 生命周期推进（ACTIVE/PAUSED/DESTROYED——条件更新防并发改写）
     */
    int updateLifecycle(@Param("platform") String platform,
                        @Param("botId") String botId,
                        @Param("groupId") String groupId,
                        @Param("lifecycle") String lifecycle);

    /**
     * 运营开关（SQL 维护期使用；管理面归 2.7）
     */
    int updateInService(@Param("platform") String platform,
                        @Param("botId") String botId,
                        @Param("groupId") String groupId,
                        @Param("inService") int inService);

    /**
     * 暂停记账：记暂停起点（恢复时算顺延差值）
     */
    int markPaused(@Param("platform") String platform,
                   @Param("botId") String botId,
                   @Param("groupId") String groupId,
                   @Param("pausedAt") java.time.LocalDateTime pausedAt);

    /**
     * 恢复：累计暂停时长累加差值、清暂停起点、置 ACTIVE
     */
    int resumeAndAccumulate(@Param("platform") String platform,
                            @Param("botId") String botId,
                            @Param("groupId") String groupId,
                            @Param("addMillis") long addMillis,
                            @Param("resumedAt") java.time.LocalDateTime resumedAt);
}
