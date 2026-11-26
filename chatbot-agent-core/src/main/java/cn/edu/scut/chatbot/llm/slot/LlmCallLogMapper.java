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
}
