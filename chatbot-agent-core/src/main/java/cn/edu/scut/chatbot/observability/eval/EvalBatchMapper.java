package cn.edu.scut.chatbot.observability.eval;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * eval_batch 表 Mapper：批次状态机写入与批次列表查询。
 */
@Mapper
public interface EvalBatchMapper {

    int insert(EvalBatchDO row);

    int finish(@Param("id") long id,
               @Param("status") String status,
               @Param("report") String report,
               @Param("costStat") String costStat,
               @Param("finishedAt") LocalDateTime finishedAt);

    EvalBatchDO selectById(@Param("id") long id);

    List<EvalBatchDO> selectPage(@Param("batchKind") String batchKind,
                                 @Param("offset") int offset,
                                 @Param("size") int size);
}
