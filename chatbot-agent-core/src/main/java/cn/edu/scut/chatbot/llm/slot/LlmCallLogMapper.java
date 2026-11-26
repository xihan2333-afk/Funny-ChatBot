package cn.edu.scut.chatbot.llm.slot;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * llm_call_log 表 Mapper（CostRecorder 批量落账 + IT 断言读取 + 成本报表聚合）。
 */
@Mapper
public interface LlmCallLogMapper {

    int insertBatch(List<LlmCallLogDO> rows);

    List<LlmCallLogDO> selectLatest(int limit);

    /**
     * 成本报表聚合（is_replay=0 线上口径，SC-005）：dim 取 GROUP / SLOT / MODEL / DAY
     */
    List<LlmCallLogAggregation> selectCostAggregation(@Param("dim") String dim,
                                                      @Param("from") LocalDateTime from,
                                                      @Param("to") LocalDateTime to,
                                                      @Param("groupId") String groupId);

    /**
     * 窗口内全表 token 汇总（缓存命中占比口径：cached / input）
     */
    LlmCallLogAggregation selectTokenTotals(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to,
                                            @Param("groupId") String groupId);

    /**
     * 窗口内 TTFT 毫秒列表（分位在服务层计算）
     */
    List<Long> selectTtftMillis(@Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                @Param("groupId") String groupId);

    /**
     * 某场景（run_kind）当前最大行 id——评估批次 cost_stat 的水位基线
     * （落账 created_at 是 DB 时钟，应用侧时间窗会与部署时区错位，水位法免疫）
     */
    Long selectMaxIdByRunKind(@Param("runKind") String runKind);

    /**
     * 重放落账当前最大行 id（is_replay=1 水位——replay 批次 cost_stat 基线，
     * 重放 Run 的 run_kind 仍是 turn/lightTask，只有 is_replay 能圈定重放口径）
     */
    Long selectMaxReplayId();

    /**
     * 重放口径（is_replay=1）自水位 id 之后的 token 与成本汇总——replay 批次
     * cost_stat 取数（与线上报表隔离，SC-005）
     */
    LlmCallLogAggregation selectReplayTotalsSinceId(@Param("sinceId") long sinceId);

    /**
     * 某场景（run_kind）自水位 id 之后的 token 与成本汇总——评估批次 cost_stat 取数
     * （eval 场景独立于线上口径：不带 is_replay 过滤）
     */
    LlmCallLogAggregation selectTotalsByRunKindSinceId(@Param("runKind") String runKind,
                                                       @Param("sinceId") long sinceId);

    /**
     * 高成本 Run（经验挖掘三类信号之一）：线上口径按 run_id 聚合、成本超阈值最近优先
     */
    List<LlmCallLogAggregation> selectTopCostRuns(@Param("from") LocalDateTime from,
                                                  @Param("to") LocalDateTime to,
                                                  @Param("minCost") java.math.BigDecimal minCost,
                                                  @Param("limit") int limit);
}
