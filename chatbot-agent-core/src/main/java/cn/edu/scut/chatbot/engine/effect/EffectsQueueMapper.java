package cn.edu.scut.chatbot.engine.effect;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * effects_queue 表 Mapper：入队 ON DUPLICATE KEY 合并、乐观抢锁、成功删行、退避更新、启动恢复。
 */
@Mapper
public interface EffectsQueueMapper {

    /**
     * 入队（同 key 合并）：uk_merge 撞键刷新载荷与 next_run_at（保次序——重复入队不插新行）
     */
    int enqueueMerge(EffectsQueueDO effect);

    /**
     * 取到期可执行的 pending 行（扫描循环每轮拉取）
     */
    List<EffectsQueueDO> selectDue(@Param("now") java.time.LocalDateTime now,
                                   @Param("limit") int limit);

    /**
     * 乐观抢锁：条件 UPDATE 置 running（影响行数 0 即并发冲突，跳过该行）
     */
    int claimForRun(@Param("id") long id, @Param("now") java.time.LocalDateTime now);

    /**
     * 执行成功删行（队列不是日志——成功即消失）
     */
    int deleteById(@Param("id") long id);

    /**
     * 可重试失败：退避更新（retry_count+1、next_run_at = now + 2^n 秒、置回 pending）
     */
    int scheduleRetry(@Param("id") long id,
                      @Param("retryCount") int retryCount,
                      @Param("nextRunAt") java.time.LocalDateTime nextRunAt);

    /**
     * 超限置 failed（落告警、不再重试；人工修复后重置）
     */
    int markFailed(@Param("id") long id);

    /**
     * 启动恢复：全部 running 行批量置回 pending（崩溃续跑——行未删即动作未完成）
     */
    int recoverRunningToPending();

    /**
     * 按 key 查行（测试断言用）
     */
    EffectsQueueDO selectByMergeKey(@Param("platform") String platform,
                                    @Param("botId") String botId,
                                    @Param("groupId") String groupId,
                                    @Param("kind") String kind);

    /**
     * 全表行数（测试断言用）
     */
    long countAll();
}
