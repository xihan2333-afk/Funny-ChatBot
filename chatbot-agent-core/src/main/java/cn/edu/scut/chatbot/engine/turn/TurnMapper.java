package cn.edu.scut.chatbot.engine.turn;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * turn 表 Mapper：DB 条件 UPDATE 推进（research R5 双闸的第二道——影响行数 0 即并发冲突）。
 */
@Mapper
public interface TurnMapper {

    int insertTurn(TurnDO turn);

    TurnDO selectById(@Param("id") long id);

    /**
     * 该群全部 GENERATING/SETTLING 的 Turn（单飞判定：非空即占用中）
     */
    List<TurnDO> selectActiveByGroup(@Param("platform") String platform,
                                     @Param("botId") String botId,
                                     @Param("groupId") String groupId);

    /**
     * 条件 UPDATE 推进状态（WHERE status=expectedStatus——影响行数 0 即并发冲突）
     */
    int advanceStatus(@Param("id") long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus,
                      @Param("finishedAt") LocalDateTime finishedAt,
                      @Param("silentReason") String silentReason,
                      @Param("errorCode") String errorCode);

    /**
     * 追加 outbox 关联（生成完成登记出事件清单）
     */
    int appendOutboxIds(@Param("id") long id, @Param("outboxIds") String outboxIdsJson);

    /**
     * 同批合并：并入新触发器（排队中的 Turn 吸收后续 accept）
     */
    int updateTriggerIds(@Param("id") long id, @Param("triggerIds") String triggerIdsJson);

    /**
     * 该群排队中的 ACCEPTED Turn（先来后到）
     */
    List<TurnDO> selectQueuedByGroup(@Param("platform") String platform,
                                     @Param("botId") String botId,
                                     @Param("groupId") String groupId);

    /**
     * 重启恢复：全部非终态 Turn（重驱前按 turn_id 查 outbox 在途出事件防重复发言）
     */
    List<TurnDO> selectNonTerminalAll();

    /**
     * 排队超时的 ACCEPTED Turn（H2：排队超时放弃扫描）
     */
    java.util.List<TurnDO> selectTimedOutQueued(@Param("deadline") java.time.LocalDateTime deadline);

    /**
     * 窗口内已完成 Turn 的端到端时长列表（毫秒，accepted_at → finished_at，时延报表分位用）
     */
    List<Long> selectFinishedDurations(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("groupId") String groupId);

    /**
     * 窗口内 Turn 按状态计数（质量报表：成功率 / 沉默比例）
     */
    List<TurnStatusCount> countByStatus(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to,
                                        @Param("groupId") String groupId);

    /**
     * 窗口内指定错误码前缀的失败 Turn 计数（槽位缺失告警：error_code 形如
     * "llm.config_missing:明细"——前缀匹配）
     */
    long countByErrorCodePrefix(@Param("errorCodePrefix") String errorCodePrefix,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    /**
     * 窗口内该群终态 Turn（评估每日抽样与手动圈选的样本来源，最近优先）
     */
    List<TurnDO> selectTerminalByGroupWindow(@Param("platform") String platform,
                                             @Param("botId") String botId,
                                             @Param("groupId") String groupId,
                                             @Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to,
                                             @Param("limit") int limit);

    /**
     * 窗口内 FAILED Turn（经验挖掘三类信号之一，最近优先）
     */
    List<TurnDO> selectFailedBetween(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("limit") int limit);

    /**
     * 按群聚合指定时间之后的 Turn 总数与成功数（总览页 24h 健康卡片）
     */
    List<GroupTurnStats> countGroupTurnsSince(@Param("from") LocalDateTime from);

    List<TurnDO> selectPaged(@Param("platform") String platform,
                             @Param("botId") String botId,
                             @Param("groupId") String groupId,
                             @Param("status") String status,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    long countPaged(@Param("platform") String platform,
                    @Param("botId") String botId,
                    @Param("groupId") String groupId,
                    @Param("status") String status,
                    @Param("from") LocalDateTime from,
                    @Param("to") LocalDateTime to);
}
