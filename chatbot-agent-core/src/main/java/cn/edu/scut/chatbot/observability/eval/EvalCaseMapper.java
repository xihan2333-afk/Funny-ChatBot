package cn.edu.scut.chatbot.observability.eval;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * eval_case 表 Mapper：三层资产（seed / golden / fault）同库读写。
 */
@Mapper
public interface EvalCaseMapper {

    int insert(EvalCaseDO row);

    EvalCaseDO selectById(@Param("id") long id);

    int updateRubric(@Param("id") long id,
                     @Param("rubric") String rubric,
                     @Param("rubricVersion") int rubricVersion);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    List<EvalCaseDO> selectByFilter(@Param("caseType") String caseType,
                                    @Param("status") String status,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    /**
     * 可回测题目（status=ready，按类型圈选；replay 批次的评测集范围取数）
     */
    List<EvalCaseDO> selectReadyByTypes(@Param("caseTypes") List<String> caseTypes,
                                        @Param("limit") int limit);
}
