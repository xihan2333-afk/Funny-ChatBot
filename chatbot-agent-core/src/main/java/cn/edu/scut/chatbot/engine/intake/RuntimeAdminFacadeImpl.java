package cn.edu.scut.chatbot.engine.intake;

import cn.edu.scut.chatbot.engine.outbox.OutboxDO;
import cn.edu.scut.chatbot.engine.outbox.OutboxMapper;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskExecutionLogDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.engine.task.TaskStatus;
import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.management.EngineCommandFacades;
import cn.edu.scut.chatbot.management.EngineViews;
import cn.edu.scut.chatbot.management.PageResult;
import cn.edu.scut.chatbot.security.gatepolicy.CostBreakerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 运行时监控与受控干预门面实现（A6）。
 */
@Service
public class RuntimeAdminFacadeImpl implements EngineCommandFacades.EngineRuntimeAdminFacade {

    private final GroupMapper groupMapper;
    private final TurnMapper turnMapper;
    private final TaskMapper taskMapper;
    private final TriggerEventMapper triggerEventMapper;
    private final OutboxMapper outboxMapper;
    private final CostBreakerService costBreakerService;
    private final Clock clock;

    public RuntimeAdminFacadeImpl(GroupMapper groupMapper,
                                  TurnMapper turnMapper,
                                  TaskMapper taskMapper,
                                  TriggerEventMapper triggerEventMapper,
                                  OutboxMapper outboxMapper,
                                  CostBreakerService costBreakerService,
                                  Clock clock) {
        this.groupMapper = groupMapper;
        this.turnMapper = turnMapper;
        this.taskMapper = taskMapper;
        this.triggerEventMapper = triggerEventMapper;
        this.outboxMapper = outboxMapper;
        this.costBreakerService = costBreakerService;
        this.clock = clock;
    }

    @Override
    public EngineViews.RuntimeOverviewView runtimeOverview() {
        CostBreakerService.BreakerSnapshot breaker = costBreakerService.aggregateDaily();
        double todaySpend = breaker != null && breaker.globalUsage() != null ? breaker.globalUsage().doubleValue() : 0.0;
        double limit = breaker != null && breaker.globalLimit() != null ? breaker.globalLimit().doubleValue() : 0.0;
        boolean globalTripped = breaker != null && breaker.globalTripped();

        List<EngineViews.RuntimeGroupStatusView> groups = new ArrayList<>();
        for (GroupDO g : groupMapper.selectAll()) {
            long runningTurns = turnMapper.selectActiveByGroup(g.getPlatform(), g.getBotId(), g.getGroupId()).size();
            long runningTasks = 0;
            long activeWorkers = 0;
            for (TaskDO t : taskMapper.selectByGroup(g.getPlatform(), g.getBotId(), g.getGroupId())) {
                TaskStatus st = TaskStatus.valueOf(t.getStatus());
                if (!st.isTerminal()) {
                    runningTasks++;
                    if (st == TaskStatus.RUNNING) activeWorkers++;
                }
            }
            String scope = CostBreakerService.groupScope(g.getPlatform(), g.getBotId(), g.getGroupId());
            boolean groupTripped = breaker != null && breaker.trippedGroups() != null
                    && Boolean.TRUE.equals(breaker.trippedGroups().get(scope));
            groups.add(new EngineViews.RuntimeGroupStatusView(
                    g.getPlatform(), g.getBotId(), g.getGroupId(), g.getGroupName(),
                    g.getLifecycle(), g.getInService() != null && g.getInService() == 1,
                    runningTurns, runningTasks, activeWorkers, 0, 10, groupTripped));
        }
        return new EngineViews.RuntimeOverviewView(globalTripped, todaySpend, limit, groups);
    }

    @Override
    public PageResult<EngineViews.TurnSummaryView> listTurns(
            String platform, String botId, String groupId, String status,
            String fromIso, String toIso, int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        LocalDateTime from = parseIso(fromIso);
        LocalDateTime to = parseIso(toIso);
        long total = turnMapper.countPaged(platform, botId, groupId, status, from, to);
        List<EngineViews.TurnSummaryView> items = turnMapper
                .selectPaged(platform, botId, groupId, status, from, to, PageResult.offset(p, ps), ps).stream()
                .map(t -> new EngineViews.TurnSummaryView(
                        t.getId(), t.getPlatform(), t.getBotId(), t.getGroupId(),
                        t.getStatus(), t.getSilentReason(), t.getErrorCode(),
                        t.getAcceptedAt() != null ? t.getAcceptedAt().toString() : null,
                        t.getFinishedAt() != null ? t.getFinishedAt().toString() : null))
                .toList();
        return PageResult.of(items, total, p, ps);
    }

    @Override
    public EngineViews.TurnDetailView getTurnDetail(long turnId) {
        TurnDO t = turnMapper.selectById(turnId);
        if (t == null) {
            throw new ChatBotException("mgmt.not_found", "Turn 不存在 id=" + turnId);
        }
        List<TriggerEventDO> triggers = triggerEventMapper.selectByTurn(turnId);
        List<OutboxDO> outboxes = outboxMapper.selectByTurn(turnId);

        String traceId = null;
        for (TriggerEventDO tr : triggers) {
            traceId = extractTraceId(tr.getTraceContext());
            if (traceId != null) break;
        }
        if (traceId == null) {
            for (OutboxDO ob : outboxes) {
                traceId = extractTraceId(ob.getTraceContext());
                if (traceId != null) break;
            }
        }

        List<EngineViews.TriggerEventView> triggerViews = triggers.stream()
                .map(tr -> new EngineViews.TriggerEventView(
                        tr.getId(), tr.getGroupId(), tr.getTriggerKind(), tr.getEventType(),
                        tr.getDedupKey(), tr.getDecision(), tr.getDecisionLayer(),
                        tr.getDecisionReason(), tr.getTurnId()))
                .toList();

        List<EngineViews.OutboxItemView> outboxViews = outboxes.stream()
                .map(ob -> new EngineViews.OutboxItemView(
                        ob.getId(), ob.getType(), ob.getStatus(), ob.getReport(), ob.getErrorCode()))
                .toList();

        return new EngineViews.TurnDetailView(
                t.getId(), t.getPlatform(), t.getBotId(), t.getGroupId(), t.getStatus(),
                t.getSilentReason(), t.getErrorCode(),
                t.getAcceptedAt() != null ? t.getAcceptedAt().toString() : null,
                t.getFinishedAt() != null ? t.getFinishedAt().toString() : null,
                traceId, triggerViews, outboxViews);
    }

    @Override
    public PageResult<EngineViews.TaskSummaryView> listTasks(
            String platform, String botId, String groupId, String status,
            String mandateSource, String category, int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);

        List<TaskDO> all = new ArrayList<>();
        if (platform != null && !platform.isBlank() && botId != null && !botId.isBlank()
                && groupId != null && !groupId.isBlank()) {
            all.addAll(taskMapper.selectByGroup(platform, botId, groupId));
        } else {
            for (GroupDO g : groupMapper.selectAll()) {
                all.addAll(taskMapper.selectByGroup(g.getPlatform(), g.getBotId(), g.getGroupId()));
            }
        }

        List<EngineViews.TaskSummaryView> filtered = all.stream()
                .filter(t -> status == null || status.isBlank() || status.equals(t.getStatus()))
                .filter(t -> mandateSource == null || mandateSource.isBlank() || mandateSource.equals(t.getMandateSource()))
                .filter(t -> category == null || category.isBlank() || category.equals(t.getCategory()))
                .sorted(Comparator.comparing(TaskDO::getId).reversed())
                .map(t -> new EngineViews.TaskSummaryView(
                        t.getId(), t.getPlatform(), t.getBotId(), t.getGroupId(),
                        t.getTaskKind(), t.getCategory(), t.getStatus(), t.getMandateSource(),
                        t.getScheduleAt() != null ? t.getScheduleAt().toString() : null,
                        t.getErrorCode(),
                        t.getCreatedAt() != null ? t.getCreatedAt().toString() : null))
                .toList();

        int total = filtered.size();
        int fromIdx = Math.min(PageResult.offset(p, ps), total);
        int toIdx = Math.min(fromIdx + ps, total);
        return PageResult.of(filtered.subList(fromIdx, toIdx), total, p, ps);
    }

    @Override
    public EngineViews.TaskDetailView getTaskDetail(long taskId) {
        TaskDO t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new ChatBotException("mgmt.not_found", "Task 不存在 id=" + taskId);
        }
        List<EngineViews.TaskExecutionLogView> logs = taskMapper.selectExecutionLogs(taskId).stream()
                .map(l -> new EngineViews.TaskExecutionLogView(
                        l.getId(), l.getRecordKind(), l.getNote(),
                        l.getCreatedAt() != null ? l.getCreatedAt().toString() : null))
                .toList();
        return new EngineViews.TaskDetailView(
                t.getId(), t.getPlatform(), t.getBotId(), t.getGroupId(),
                t.getTaskKind(), t.getCategory(), t.getStatus(),
                t.getMandateSource(), t.getMandateRef(), t.getCreatorUserId(),
                t.getSnapshot(),
                t.getScheduleAt() != null ? t.getScheduleAt().toString() : null,
                t.getResult(), t.getErrorCode(),
                TaskStatus.RUNNING.name().equals(t.getStatus()),
                logs);
    }

    @Override
    @Transactional
    public void retryFailedTask(long taskId, String operator) {
        TaskDO t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new ChatBotException("mgmt.not_found", "Task 不存在 id=" + taskId);
        }
        if (!TaskStatus.FAILED.name().equals(t.getStatus())) {
            throw new CommandRejectedException("仅允许重试 FAILED 状态的任务，当前状态：" + t.getStatus());
        }
        int updated = taskMapper.advanceStatus(taskId, TaskStatus.FAILED.name(), TaskStatus.SCHEDULED.name());
        if (updated == 0) {
            throw new CommandRejectedException("任务状态并发变更，重试失败");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        taskMapper.updateScheduleAt(taskId, now);
        TaskExecutionLogDO log = new TaskExecutionLogDO();
        log.setTaskId(taskId);
        log.setRecordKind(TaskExecutionLogDO.KIND_ROUND);
        log.setNote("管理员手动重试失败任务 operator=" + (operator != null ? operator : "admin"));
        log.setCreatedAt(now);
        taskMapper.insertExecutionLog(log);
    }

    @Override
    public PageResult<EngineViews.DecisionItemView> listDecisions(
            String platform, String botId, String groupId, String decision,
            String decisionLayer, String fromIso, String toIso, int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        LocalDateTime from = parseIso(fromIso);
        LocalDateTime to = parseIso(toIso);

        List<TriggerEventDO> all = new ArrayList<>();
        if (platform != null && !platform.isBlank() && botId != null && !botId.isBlank()
                && groupId != null && !groupId.isBlank()) {
            all.addAll(triggerEventMapper.selectAllByGroup(platform, botId, groupId));
        } else {
            for (GroupDO g : groupMapper.selectAll()) {
                all.addAll(triggerEventMapper.selectAllByGroup(g.getPlatform(), g.getBotId(), g.getGroupId()));
            }
        }

        List<EngineViews.DecisionItemView> filtered = all.stream()
                .filter(d -> decision == null || decision.isBlank() || decision.equals(d.getDecision()))
                .filter(d -> decisionLayer == null || decisionLayer.isBlank() || decisionLayer.equals(d.getDecisionLayer()))
                .filter(d -> from == null || (d.getOccurredAt() != null && !d.getOccurredAt().isBefore(from)))
                .filter(d -> to == null || (d.getOccurredAt() != null && d.getOccurredAt().isBefore(to)))
                .sorted(Comparator.comparing(TriggerEventDO::getId).reversed())
                .map(d -> new EngineViews.DecisionItemView(
                        d.getId(), d.getPlatform(), d.getBotId(), d.getGroupId(),
                        d.getTriggerKind(), d.getEventType(), d.getDecision(),
                        d.getDecisionLayer(), d.getDecisionReason(), d.getBatchDigest(),
                        d.getTurnId(),
                        d.getOccurredAt() != null ? d.getOccurredAt().toString() : null,
                        d.getDecidedAt() != null ? d.getDecidedAt().toString() : null))
                .toList();

        int total = filtered.size();
        int fromIdx = Math.min(PageResult.offset(p, ps), total);
        int toIdx = Math.min(fromIdx + ps, total);
        return PageResult.of(filtered.subList(fromIdx, toIdx), total, p, ps);
    }

    /**
     * 解析 W3C traceparent（00-&lt;32hex traceId&gt;-&lt;16hex spanId&gt;-&lt;flags&gt;）提取 traceId
     */
    public static String extractTraceId(String traceContext) {
        if (traceContext == null || traceContext.isBlank()) {
            return null;
        }
        String trimmed = traceContext.trim();
        if (trimmed.contains("-")) {
            String[] parts = trimmed.split("-");
            if (parts.length >= 2 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        return trimmed.length() == 32 ? trimmed : null;
    }

    private static LocalDateTime parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
