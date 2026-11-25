package cn.edu.scut.chatbot.memory.persona;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * persona_evolution_proposal 表 Mapper（US7 演进管线）：
 * 提案落库待审、审阅状态迁移、驳回留档可查。
 */
@Mapper
public interface PersonaEvolutionProposalMapper {

    int insertProposal(PersonaEvolutionProposalDO proposal);

    PersonaEvolutionProposalDO selectById(@Param("id") long id);

    /**
     * 某人格全部提案（驳回留档进下期参考——新提案聚合时参考历史）
     */
    List<PersonaEvolutionProposalDO> selectByPersona(@Param("personaId") long personaId);

    /**
     * 审阅状态迁移（CAS：仅 pending_review 可迁移——防重复审阅）
     */
    int updateReview(@Param("id") long id,
                     @Param("status") String status,
                     @Param("reviewer") String reviewer,
                     @Param("remark") String remark);

    /**
     * 管理面编辑提案条目后接受（修改后接受的载体）
     */
    int updateProposalItems(@Param("id") long id, @Param("proposalItems") String proposalItems);
}
