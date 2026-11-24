package cn.edu.scut.chatbot.engine.task;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * task / task_execution_log / task_output_archive 表 Mapper。
 */
@Mapper
public interface TaskMapper {

    int insertTask(TaskDO task);

    TaskDO selectById(@Param("id") long id);

    /**
     * 到期可推进（调度扫描键：SCHEDULED/WAITING 且 schedule_at 已到）
     */
    List<TaskDO> selectDue(@Param("now") java.time.LocalDateTime now, @Param("limit") int limit);

    /**
     * 该群全部 Task（queryTasks 工具与审计）
     */
    List<TaskDO> selectByGroup(@Param("platform") String platform,
                               @Param("botId") String botId,
                               @Param("groupId") String groupId);

    /**
     * 条件 UPDATE 推进（影响行数 0 即并发冲突）
     */
    int advanceStatus(@Param("id") long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus);

    int updateScheduleAt(@Param("id") long id, @Param("scheduleAt") java.time.LocalDateTime scheduleAt);

    int updateResult(@Param("id") long id,
                     @Param("result") String resultJson,
                     @Param("errorCode") String errorCode,
                     @Param("finishedAt") java.time.LocalDateTime finishedAt);

    /**
     * 重启恢复：全部 RUNNING/WAITING（从最后检查点恢复且动作留痕）
     */
    List<TaskDO> selectRecoverable();

    /**
     * 全部 WAITING 任务（条件唤醒求值与超期判定的扫描口径）
     */
    List<TaskDO> selectWaiting();

    /**
     * 该群最旧 RUNNING 的 light Task（generation Effect 指针驱动：轻任务路径）
     */
    TaskDO selectOldestRunningLightByGroup(@Param("platform") String platform,
                                           @Param("botId") String botId,
                                           @Param("groupId") String groupId);

    // ===== execution_log =====

    int insertExecutionLog(TaskExecutionLogDO log);

    List<TaskExecutionLogDO> selectExecutionLogs(@Param("taskId") long taskId);

    // ===== output_archive =====

    int insertArchiveChunk(TaskOutputArchiveDO chunk);

    List<TaskOutputArchiveDO> selectArchiveChunks(@Param("taskId") long taskId);
}
