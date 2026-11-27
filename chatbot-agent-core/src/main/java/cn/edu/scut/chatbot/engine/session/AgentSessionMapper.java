package cn.edu.scut.chatbot.engine.session;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * agent_session / transcript_entry 表 Mapper：会话惰性创建与恢复、转录只增不改。
 */
@Mapper
public interface AgentSessionMapper {

    /**
     * 惰性创建（uk_group 撞键静默——并发首交互只有一个赢）
     */
    int insertSession(AgentSessionDO session);

    AgentSessionDO selectByGroup(@Param("platform") String platform,
                                 @Param("botId") String botId,
                                 @Param("groupId") String groupId);

    int updatePersona(@Param("id") long id, @Param("personaId") long personaId);

    int updateWindowLevel(@Param("id") long id, @Param("windowLevel") String windowLevel);

    int updateEmotion(@Param("id") long id, @Param("emotion") String emotionJson);

    int updateSummaryPointer(@Param("id") long id, @Param("summaryEntryId") long summaryEntryId);

    /**
     * 群销毁时卸载会话（历史条目按群配置策略处理）
     */
    int markDestroyed(@Param("platform") String platform,
                      @Param("botId") String botId,
                      @Param("groupId") String groupId);

    // ===== transcript_entry =====

    /**
     * 批量插入（轮次原子落库：一个事务写该轮全部消息）
     */
    int insertTranscriptBatch(@Param("entries") List<TranscriptEntryDO> entries);

    /**
     * 按 occurredAt 倒序取近期条目（L1 有界上下文 / 投影取数）
     */
    List<TranscriptEntryDO> selectRecent(@Param("sessionId") long sessionId,
                                         @Param("limit") int limit);

    /**
     * 按 occurredAt 正序全量取（投影组装——窗口是视图）
     */
    List<TranscriptEntryDO> selectOrdered(@Param("sessionId") long sessionId);

    /**
     * 按 runId 取（审计：该 Run 产生了哪些条目）
     */
    List<TranscriptEntryDO> selectByRun(@Param("runId") long runId);

    /**
     * 会话按水位档位计数（上下文报表）
     */
    List<WindowLevelCount> countWindowLevel();

    /**
     * 窗口内转录 (kind, occurred_at) 轻量序列（被回复率跟帖判定与压缩频率计数）
     */
    List<TranscriptKindTime> selectTranscriptKindTimes(@Param("from") java.time.LocalDateTime from,
                                                       @Param("to") java.time.LocalDateTime to,
                                                       @Param("groupId") String groupId);

    /**
     * 删除该会话的重放临时转录（dry-run Pass@k 每次执行前清场：前情种子经
     * appendTranscript(runId=null) 落库——run_id 为空的保留；上次重放 Run 落库的
     * 发言与工具结果清空，k 次执行彼此独立）
     */
    int deleteReplayTransient(@Param("sessionId") long sessionId);

    /**
     * 个体数据清除（US12）：删除指定群内该用户的发言转录条目（kind='user' 且 senderUserId 匹配）
     */
    int deleteUserTranscriptByGroup(@Param("platform") String platform,
                                    @Param("botId") String botId,
                                    @Param("groupId") String groupId,
                                    @Param("userId") String userId);
}
