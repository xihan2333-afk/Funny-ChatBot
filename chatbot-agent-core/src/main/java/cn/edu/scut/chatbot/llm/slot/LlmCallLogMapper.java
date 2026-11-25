package cn.edu.scut.chatbot.llm.slot;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * llm_call_log 表 Mapper（CostRecorder 批量落账 + IT 断言读取）。
 */
@Mapper
public interface LlmCallLogMapper {

    int insertBatch(List<LlmCallLogDO> rows);

    List<LlmCallLogDO> selectLatest(int limit);
}
