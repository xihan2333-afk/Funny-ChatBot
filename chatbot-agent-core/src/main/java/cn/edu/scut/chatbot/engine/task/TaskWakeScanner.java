package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Task 时钟驱动扫描器（设计 :452/:458）：WAITING 任务的两条推进线——
 * <ul>
 *   <li>条件唤醒：wake_condition 求值命中 → advanceTask（WAITING→RUNNING），
 *       唤醒后的发言同样走时机评估与生成回执闭环</li>
 *   <li>超期判定：expireAt 已过 → EXPIRED 终态留痕（过期任务不再等）</li>
 * </ul>
 *
 * <p>条件形态（声明式 JSON，第一版两种；语义化群状态判定归 2.4 群状态对象）：
 * {@code {"kind":"groupActive"}}——群话题再热即唤醒（默认近 10 分钟有新消息）；
 * {@code {"kind":"groupQuiet","minutes":N}}——群静默满 N 分钟后唤醒。
 * 求值数据源是 trigger_event 表（群消息流的事实留档）。
 */
@Component
public class TaskWakeScanner {

    private static final Logger log = LoggerFactory.getLogger(TaskWakeScanner.class);

    /**
     * groupActive 条件的「再热」判定窗口（分钟）
     */
    private static final long GROUP_ACTIVE_WINDOW_MINUTES = 10;

    private final TaskMapper taskMapper;
    private final TaskCoordinator taskCoordinator;
    private final TriggerEventMapper triggerEventMapper;
    private final Clock clock;

    public TaskWakeScanner(TaskMapper taskMapper,
                           TaskCoordinator taskCoordinator,
                           TriggerEventMapper triggerEventMapper,
                           Clock clock) {
        this.taskMapper = taskMapper;
        this.taskCoordinator = taskCoordinator;
        this.triggerEventMapper = triggerEventMapper;
        this.clock = clock;
    }

    /**
     * 扫描周期 15 秒（与 Turn 排队超时扫描同档；唤醒不追求秒级实时）
     */
    @Scheduled(fixedDelay = 15000)
    public void scanWaitingTasks() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (TaskDO task : taskMapper.selectWaiting()) {
            if (expireIfNeeded(task, now)) {
                continue;
            }
            if (wakeConditionHit(task, now)) {
                log.info("条件唤醒命中 taskId={} wakeCondition={}", task.getId(), task.getWakeCondition());
                taskCoordinator.advanceTask(task.getId());
            }
        }
    }

    /**
     * 超期判定（WAITING → EXPIRED）：过期任务安静终结并留痕
     *
     * @return true = 已置 EXPIRED
     */
    private boolean expireIfNeeded(TaskDO task, LocalDateTime now) {
        if (task.getExpireAt() == null || !task.getExpireAt().isBefore(now)) {
            return false;
        }
        int updated = taskMapper.advanceStatus(task.getId(), TaskStatus.WAITING.name(),
                TaskStatus.EXPIRED.name());
        if (updated > 0) {
            taskMapper.updateResult(task.getId(), null,
                    "engine.task.expired", now);
            taskMapper.insertExecutionLog(executionLog(task.getId(),
                    "超期终结：expireAt=" + task.getExpireAt()));
        }
        return true;
    }

    /**
     * wake_condition 求值（解析失败按不命中处理——坏条件不唤醒也不报错）
     */
    boolean wakeConditionHit(TaskDO task, LocalDateTime now) {
        if (task.getWakeCondition() == null || task.getWakeCondition().isBlank()) {
            return false;
        }
        try {
            JsonNode condition = JsonMappers.shared().readTree(task.getWakeCondition());
            String kind = condition.path("kind").asText("");
            return switch (kind) {
                case "groupActive" -> groupActiveSince(task,
                        now.minusMinutes(GROUP_ACTIVE_WINDOW_MINUTES));
                case "groupQuiet" -> groupQuietSince(task,
                        now.minusMinutes(condition.path("minutes").asLong(30)));
                default -> false;
            };
        } catch (Exception e) {
            log.warn("wake_condition 解析失败按不命中处理 taskId={}", task.getId(), e);
            return false;
        }
    }

    /**
     * 群话题再热判定：判定窗口内有新触发器（含群消息与事件）
     */
    private boolean groupActiveSince(TaskDO task, LocalDateTime since) {
        LocalDateTime last = lastTriggerAt(task);
        return last != null && !last.isBefore(since);
    }

    /**
     * 群静默判定：最近一条触发器早于静默起点（无任何触发器视为已静默）
     */
    private boolean groupQuietSince(TaskDO task, LocalDateTime quietSince) {
        LocalDateTime last = lastTriggerAt(task);
        return last == null || last.isBefore(quietSince);
    }

    /**
     * 该群最近一条触发器时间（MAX 聚合——trigger_event 只增，全量拉取会随历史线性变慢）
     */
    private LocalDateTime lastTriggerAt(TaskDO task) {
        return triggerEventMapper.selectLastOccurredAt(
                task.getPlatform(), task.getBotId(), task.getGroupId());
    }

    private TaskExecutionLogDO executionLog(long taskId, String note) {
        TaskExecutionLogDO logEntry = new TaskExecutionLogDO();
        logEntry.setTaskId(taskId);
        logEntry.setRecordKind(TaskExecutionLogDO.KIND_ROUND);
        logEntry.setNote(note);
        return logEntry;
    }
}
