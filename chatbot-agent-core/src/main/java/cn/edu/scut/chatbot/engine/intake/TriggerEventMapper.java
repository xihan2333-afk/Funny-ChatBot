package cn.edu.scut.chatbot.engine.intake;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * trigger_event 表 Mapper：登记即写、决策回填、uk_dedup 唯一索引拒插即幂等。
 */
@Mapper
public interface TriggerEventMapper {

    /**
     * 登记插入：uk_dedup 撞键抛 DuplicateKeyException（调用方捕获即知重复）
     */
    int insertTrigger(TriggerEventDO triggerEvent);

    /**
     * 按幂等键查已存在行（重复投递返回已存在，不重复登记）
     */
    TriggerEventDO selectByDedupKey(@Param("platform") String platform,
                                    @Param("botId") String botId,
                                    @Param("dedupKey") String dedupKey);

    /**
     * 决策回填（L0/L1/degrade 共用）
     */
    int updateDecision(@Param("id") long id,
                       @Param("decision") String decision,
                       @Param("decisionLayer") String decisionLayer,
                       @Param("decisionReason") String decisionReason,
                       @Param("batchDigest") String batchDigest,
                       @Param("decidedAt") java.time.LocalDateTime decidedAt);

    /**
     * accept 后回填归属 Turn
     */
    int updateTurnId(@Param("id") long id, @Param("turnId") long turnId);

    /**
     * 该群全部 pending 触发器（旁观缓冲即 pending 行集——research R6）
     */
    List<TriggerEventDO> selectPendingByGroup(@Param("platform") String platform,
                                              @Param("botId") String botId,
                                              @Param("groupId") String groupId);

    /**
     * 该群全部触发器（不限决策状态——断言与审计用）
     */
    List<TriggerEventDO> selectAllByGroup(@Param("platform") String platform,
                                          @Param("botId") String botId,
                                          @Param("groupId") String groupId);

    /**
     * 该群最近一条触发器时间（条件唤醒的群活跃/静默判定——MAX 聚合免全量拉取）
     */
    java.time.LocalDateTime selectLastOccurredAt(@Param("platform") String platform,
                                                 @Param("botId") String botId,
                                                 @Param("groupId") String groupId);

    /**
     * 按 ID 批量取（L1 评估批次上下文）
     */
    List<TriggerEventDO> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 按 ID 单取
     */
    TriggerEventDO selectById(@Param("id") long id);

    /**
     * 该群最旧未交付的任务结论事件（generation Effect 指针驱动：delivery 路径；
     * turn_id 为 NULL 表示尚未交付，交付完成后回填 -1 标记）
     */
    TriggerEventDO selectOldestUndeliveredTaskDelivery(@Param("platform") String platform,
                                                       @Param("botId") String botId,
                                                       @Param("groupId") String groupId);

    /**
     * 某时刻以来该群指定类型的内部触发条数（主动开口日预算的计数输入——
     * 事实留档即状态，零新增记账）
     */
    long countInternalByTypeSince(@Param("platform") String platform,
                                  @Param("botId") String botId,
                                  @Param("groupId") String groupId,
                                  @Param("eventType") String eventType,
                                  @Param("since") java.time.LocalDateTime since);
}
