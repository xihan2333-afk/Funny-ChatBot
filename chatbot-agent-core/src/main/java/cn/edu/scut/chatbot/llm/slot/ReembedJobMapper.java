package cn.edu.scut.chatbot.llm.slot;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReembedJobMapper {

    int insert(ReembedJobDO job);

    int updateProgress(ReembedJobDO job);

    ReembedJobDO selectById(@Param("id") long id);

    List<ReembedJobDO> selectAllPaged(@Param("offset") int offset, @Param("limit") int limit);

    long countAll();

    long countActive();
}
