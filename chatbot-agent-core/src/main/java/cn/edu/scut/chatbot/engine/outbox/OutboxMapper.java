package cn.edu.scut.chatbot.engine.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * outbox 表 Mapper：先落库再投递、回执回填、在途查询（崩溃恢复防重复发言）。
 */
@Mapper
public interface OutboxMapper {

    int insertOutbox(OutboxDO outbox);

    OutboxDO selectById(@Param("id") long id);

    /**
     * 该 Turn 的全部出事件（结算：全部 settled → SETTLED；任一不可重试失败 → FAILED）
     */
    List<OutboxDO> selectByTurn(@Param("turnId") long turnId);

    /**
     * 该 Turn 的在途出事件（崩溃恢复：非空则跳过重新生成直接进结算段）
     */
    List<OutboxDO> selectPendingByTurn(@Param("turnId") long turnId);

    /**
     * 回执结算回填
     */
    int settleReport(@Param("id") long id,
                     @Param("status") String status,
                     @Param("report") String reportJson,
                     @Param("errorCode") String errorCode,
                     @Param("settledAt") java.time.LocalDateTime settledAt);

    /**
     * 待投递的 pending 行（dispatch Effect 载荷按 id 取）
     */
    List<OutboxDO> selectPendingAll(@Param("limit") int limit);

    /**
     * 投递前条件占用（H3：pending→dispatching CAS——XADD 前先占位防崩溃重投重复发言）
     */
    int claimForDispatch(@Param("id") long id, @Param("now") java.time.LocalDateTime now);

    /**
     * 投递失败回退（dispatching→pending 退避重投）
     */
    int revertDispatch(@Param("id") long id);

    /**
     * 卡死的 dispatching 行重置（XADD 前崩溃的窗口——超时重投，宁可重试不永久滞留）
     */
    int resetStaleDispatching(@Param("deadline") java.time.LocalDateTime deadline);
}
