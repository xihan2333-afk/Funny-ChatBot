package cn.edu.scut.chatbot.engine.lifecycle;

import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.engine.task.TaskStatus;
import cn.edu.scut.chatbot.engine.task.WorkerSignalRegistry;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 群生命周期管理器（spec US8、data-model 第 12 节）：
 * 暂停（冻结管线、计时顺延——一切到期计算的顺延依据是 paused_accumulated_millis）、
 * 恢复（从冻结点继续）、销毁（Task 全取消、Worker 终止、会话卸载、决策历史永久保留）。
 *
 * <p>暂停语义：新触发器照常登记但不评估（L0 群暂停规则强制忽略）、进行中 Turn 完成发送为止
 * （不硬杀）、Task 全挂起、Worker 置取消信号、该群模型调用为零（负向断言）。
 */
@Component
public class GroupLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(GroupLifecycleManager.class);

    private final GroupMapper groupMapper;
    private final TaskMapper taskMapper;
    private final TaskCoordinator taskCoordinator;
    private final WorkerSignalRegistry workerSignalRegistry;
    private final SessionManager sessionManager;
    private final GroupStateHash groupStateHash;
    private final Clock clock;

    public GroupLifecycleManager(GroupMapper groupMapper,
                                 TaskMapper taskMapper,
                                 TaskCoordinator taskCoordinator,
                                 WorkerSignalRegistry workerSignalRegistry,
                                 SessionManager sessionManager,
                                 GroupStateHash groupStateHash,
                                 Clock clock) {
        this.groupMapper = groupMapper;
        this.taskMapper = taskMapper;
        this.taskCoordinator = taskCoordinator;
        this.workerSignalRegistry = workerSignalRegistry;
        this.sessionManager = sessionManager;
        this.groupStateHash = groupStateHash;
        this.clock = clock;
    }

    /**
     * 暂停（请求类）：记暂停起点（恢复时算顺延差值）；Worker 置取消信号；
     * Task 挂起（SCHEDULED/WAITING 保持不推进——评估侧按群暂停跳过）
     */
    public void pauseGroup(String platform, String botId, String groupId) {
        groupMapper.markPaused(platform, botId, groupId, LocalDateTime.now(clock));
        cancelWorkersOfGroup(platform, botId, groupId);
        log.info("群暂停（计时随恢复顺延） group={}", groupId);
    }

    /**
     * 恢复（请求类）：累计暂停时长入账（一切计时顺延的依据）、清暂停起点、置 ACTIVE；
     * 挂起的 Task 经调度下轮自然重新入队（schedule_at 已随暂停顺延或即刻到期）
     */
    public void resumeGroup(String platform, String botId, String groupId) {
        GroupDO group = groupMapper.selectByGroup(platform, botId, groupId);
        if (group == null || group.getPausedAt() == null) {
            return;   // 未暂停（幂等）
        }
        long pausedMillis = java.time.Duration.between(
                group.getPausedAt(), LocalDateTime.now(clock)).toMillis();
        groupMapper.resumeAndAccumulate(platform, botId, groupId, pausedMillis, LocalDateTime.now(clock));
        log.info("群恢复（顺延 {}ms 入账） group={}", pausedMillis, groupId);
    }

    /**
     * 销毁（请求类）：Task 全取消、Worker 终止、AgentSession 卸载（历史沉淀策略由群配置
     * 决定——keep 保留 / discard 丢弃，本 feature 默认保留）、触发器与决策历史永久保留。
     */
    public void destroyGroup(String platform, String botId, String groupId) {
        cancelWorkersOfGroup(platform, botId, groupId);
        int cancelledTasks = 0;
        for (var task : taskMapper.selectByGroup(platform, botId, groupId)) {
            if (!TaskStatus.valueOf(task.getStatus()).isTerminal()) {
                taskMapper.advanceStatus(task.getId(), task.getStatus(), TaskStatus.CANCELLED.name());
                cancelledTasks++;
            }
        }
        sessionManager.destroySession(platform, botId, groupId);
        groupStateHash.deleteGroupState(platform, botId, groupId);
        groupMapper.updateLifecycle(platform, botId, groupId, GroupDO.LIFECYCLE_DESTROYED);
        log.info("群销毁完成（取消 {} 个任务、会话卸载、决策历史保留） group={}", cancelledTasks, groupId);
    }

    /**
     * BotEvent 生命周期联动（被踢出群 → 销毁）
     */
    public void onBotKicked(String platform, String botId, String groupId) {
        log.info("Bot 被踢出群，触发销毁联动 group={}", groupId);
        destroyGroup(platform, botId, groupId);
    }

    /**
     * 该群全部非终态 Task 的 Worker 取消（不 interrupt——只经 CancellationSignal）
     */
    private void cancelWorkersOfGroup(String platform, String botId, String groupId) {
        for (var task : taskMapper.selectByGroup(platform, botId, groupId)) {
            if (!TaskStatus.valueOf(task.getStatus()).isTerminal()) {
                workerSignalRegistry.cancelWorker(task.getId());
            }
        }
    }
}
