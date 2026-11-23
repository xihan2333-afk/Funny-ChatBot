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
}
