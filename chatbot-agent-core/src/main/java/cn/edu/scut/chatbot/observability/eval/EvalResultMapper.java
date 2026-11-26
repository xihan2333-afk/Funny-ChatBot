package cn.edu.scut.chatbot.observability.eval;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * eval_result 表 Mapper：结果落库、批次明细查询、归因回填与盲评配对取数。
 */
@Mapper
public interface EvalResultMapper {

    int insert(EvalResultDO row);

    EvalResultDO selectById(@Param("id") long id);

    List<EvalResultDO> selectByBatch(@Param("batchId") long batchId);

    int updateAttribution(@Param("id") long id, @Param("attribution") String attribution);

    /**
     * 时间窗内全部盲评行（机器行 judge_model 非空 + 人工行 judge_model 为空）——
     * 人机一致率在服务层按（batchId + turnId/caseId）配对
     */
    List<EvalResultDO> selectBlindBetween(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * 时间窗内低分（badcase）结果——经验挖掘三类信号之一
     */
    List<EvalResultDO> selectBadcaseBetween(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to,
                                            @Param("limit") int limit);
}
