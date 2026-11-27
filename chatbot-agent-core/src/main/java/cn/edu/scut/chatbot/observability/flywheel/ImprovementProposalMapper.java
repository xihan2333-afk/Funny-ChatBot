package cn.edu.scut.chatbot.observability.flywheel;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * improvement_proposal 表 Mapper：提案状态机写入与审阅列表查询。
 */
@Mapper
public interface ImprovementProposalMapper {

    /**
     * 同证据去重判定（uk_dedup 拒插是最后防线，此处先查先报）
     */
    ImprovementProposalDO selectByDedupKey(@Param("dedupKey") String dedupKey);

    int insert(ImprovementProposalDO row);

    ImprovementProposalDO selectById(@Param("id") long id);

    int updateAttribution(@Param("id") long id,
                          @Param("status") String status,
                          @Param("attribution") String attribution);

    int updateStatus(@Param("id") long id,
                     @Param("status") String status,
                     @Param("reviewNote") String reviewNote);

    List<ImprovementProposalDO> selectByFilter(@Param("status") String status,
                                               @Param("offset") int offset,
                                               @Param("size") int size);
}
