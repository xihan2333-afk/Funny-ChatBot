package cn.edu.scut.chatbot.memory.persona;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 演进五信号源的月度现查聚合 Mapper（data-model §1.6 注：不建中间表——
 * 统计周期内 GROUP BY 现查）。人格经 agent_session.persona_id 关联其全部绑定群，
 * 群维度数据按绑定群聚合（人格是多群共享的全局实体）。
 */
@Mapper
public interface PersonaEvolutionSignalsMapper {

    /**
     * 信号一（Decision 留档）：绑定群内部触发的总量与接受量（主动开口 L1 通过率）
     */
    Map<String, Object> aggregateInternalDecisions(@Param("personaId") long personaId,
                                                   @Param("since") LocalDateTime since);

    /**
     * 信号二（交互反馈统计底数）：绑定群转录的 assistant / user 条数
     * （变体分桶数据面：turn 执行记录无变体列——聚合时标注数据不足）
     */
    List<Map<String, Object>> aggregateTranscriptKinds(@Param("personaId") long personaId,
                                                       @Param("since") LocalDateTime since);

    /**
     * 信号三（记忆沉淀信号）：绑定群 preference 类 active 条目正文（话题分布原料）
     */
    List<String> selectPreferenceContents(@Param("personaId") long personaId,
                                          @Param("since") LocalDateTime since);

    /**
     * 信号四（长期情绪统计）：绑定群 emotion_log 标签分布与平均强度
     */
    List<Map<String, Object>> aggregateEmotionLabels(@Param("personaId") long personaId,
                                                     @Param("since") LocalDateTime since);
}
