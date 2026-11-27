package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import cn.edu.scut.chatbot.security.gatepolicy.CostBreakerService;
import cn.edu.scut.chatbot.security.gatepolicy.GatePolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Task 协调器（spec US4）：Task 状态机唯一写入者。创建校验 Mandate；等待→行动过轻量时机评估
 * （授权答可不可以——Mandate 已验；时机答合不合适——群状态与话题新鲜度两闸分离）；
 * 轻/重分派（light 入 generation、heavy 入 workerLaunch）；结论登记内部交付事件
 * （dedupKey 业务键、accept 直通不过闸门——授权在创建时已确立）。
 */
@Component
public class TaskCoordinator {

    /**
     * Agent 业务过程日志（agent. 前缀——任务推进是业务流水，T022）
     */
    private static final Logger log = LoggerFactory.getLogger("agent.task");

    /**
     * 时机评估的话题安静阈值：群最近消息 3 秒内 → 时机不合推迟 1 分钟
     */
    private static final long TOPIC_QUIET_REQUIRED_MILLIS = 3000;

    private final TaskMapper taskMapper;
    private final MandateGuard mandateGuard;
    private final TriggerEventMapper triggerEventMapper;
    private final GroupMapper groupMapper;
    private final EffectsQueueMapper effectsQueueMapper;
    private final WorkerSignalRegistry workerSignalRegistry;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public TaskCoordinator(TaskMapper taskMapper,
                           MandateGuard mandateGuard,
                           TriggerEventMapper triggerEventMapper,
                           GroupMapper groupMapper,
                           EffectsQueueMapper effectsQueueMapper,
                           WorkerSignalRegistry workerSignalRegistry,
                           IdGenerator idGenerator,
                           Clock clock) {
        this.taskMapper = taskMapper;
        this.mandateGuard = mandateGuard;
        this.triggerEventMapper = triggerEventMapper;
        this.groupMapper = groupMapper;
        this.effectsQueueMapper = effectsQueueMapper;
        this.workerSignalRegistry = workerSignalRegistry;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 引擎事件发布器（Task 状态机迁移发射——观测桥的闭合注册表凭 task:{taskId} 定位
     * 在途 task Span）。setter 注入可选装配：未装配时零开销，测试构造不受扰。
     */
    private cn.edu.scut.chatbot.engine.events.EngineEventPublisher engineEvents;

    @Autowired(required = false)
    void setEngineEventPublisher(cn.edu.scut.chatbot.engine.events.EngineEventPublisher publisher) {
        this.engineEvents = publisher;
    }

    /**
     * 安全闸三件（US8：成本熔断、重任务频控与越权计数、安全事件统一记录）——
     * setter 可选注入，兼容隔离单测构造。
     */
    private CostBreakerService costBreakerService;
    private GatePolicyService gatePolicyService;
    private SecurityEventRecorder securityEventRecorder;

    @Autowired(required = false)
    void setCostBreakerService(CostBreakerService service) {
        this.costBreakerService = service;
    }

    @Autowired(required = false)
    void setGatePolicyService(GatePolicyService service) {
        this.gatePolicyService = service;
    }

    @Autowired(required = false)
    void setSecurityEventRecorder(SecurityEventRecorder recorder) {
        this.securityEventRecorder = recorder;
    }

    private void emitTaskTransitioned(TaskDO task, String from, String to) {
        if (engineEvents != null) {
            engineEvents.publish(new cn.edu.scut.chatbot.engine.events.EngineEvent.TaskTransitioned(
                    task.getPlatform(), task.getBotId(), task.getGroupId(),
                    task.getId(), from, to, task.getMandateSource()));
        }
    }

    /**
     * 创建 Task（请求类）：Mandate 校验不过即拒绝（拒绝留痕——调用方记审计）。
     * 轻任务到期驱动经 taskWake Effect（schedule_at 扫描）；重任务同样 taskWake 到期后
     * 再分派 workerLaunch。
     */
    public long createTask(TaskDO task) {
        try {
            mandateGuard.assertCreatable(task);
        } catch (CommandRejectedException rejected) {
            recordMandateDenied(task, rejected.getReason());
            throw rejected;
        }
        boolean isReplay = task.getPlatform() != null && task.getPlatform().startsWith("replay::");
        if (!isReplay && TaskDO.KIND_HEAVY.equals(task.getTaskKind())) {
            if (costBreakerService != null) {
                costBreakerService.onHeavyTaskAttempt(task.getPlatform(), task.getBotId(), task.getGroupId(), task.getCreatorUserId());
            }
            if (gatePolicyService != null && !TaskDO.MANDATE_ADMIN.equals(task.getMandateSource())) {
                var userQuota = gatePolicyService.checkHeavyTaskQuota(task.getPlatform(), task.getBotId(), task.getGroupId(),
                        task.getCreatorUserId() != null ? task.getCreatorUserId() : "anonymous");
                if (!userQuota.allowed()) {
                    recordGateBlocked(task, "heavyTaskPerUserDaily", userQuota.used(), userQuota.limit());
                    throw new CommandRejectedException(
                            "你今日发起的重任务已达上限（" + userQuota.limit() + " 次），明天再试");
                }
                var groupQuota = gatePolicyService.checkHeavyTaskGroupQuota(
                        task.getPlatform(), task.getBotId(), task.getGroupId());
                if (!groupQuota.allowed()) {
                    recordGateBlocked(task, "heavyTaskPerGroupDaily", groupQuota.used(), groupQuota.limit());
                    throw new CommandRejectedException(
                            "本群今日重任务已达上限（" + groupQuota.limit() + " 次），明天再试");
                }
            }
        }
        long taskId = idGenerator.nextId();
        task.setId(taskId);
        task.setStatus(TaskStatus.SCHEDULED.name());
        if (task.getScheduleAt() == null) {
            task.setScheduleAt(LocalDateTime.now(clock));
        }
        taskMapper.insertTask(task);
        scheduleWake(taskId);
        emitTaskTransitioned(task, null, TaskStatus.SCHEDULED.name());
        return taskId;
    }

    private void recordMandateDenied(TaskDO task, String reason) {
        if (securityEventRecorder != null) {
            try {
                java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
                detail.put("category", task.getCategory());
                detail.put("mandateSource", task.getMandateSource());
                detail.put("taskKind", task.getTaskKind());
                detail.put("reason", reason);
                securityEventRecorder.record(
                        cn.edu.scut.chatbot.security.audit.SecurityEventKind.MANDATE_DENIED,
                        cn.edu.scut.chatbot.security.audit.SecurityDims.ofUser(
                                task.getPlatform(), task.getBotId(), task.getGroupId(), task.getCreatorUserId()),
                        detail, currentTraceId());
            } catch (Exception e) {
                log.warn("mandateDenied 审计记录失败（不影响拒绝语义）", e);
            }
        }
        if (gatePolicyService != null && task.getCreatorUserId() != null) {
            try {
                gatePolicyService.recordMandateDenied(task.getCreatorUserId());
            } catch (Exception e) {
                log.warn("越权小时桶计数失败 userId={}", task.getCreatorUserId(), e);
            }
        }
    }

    private void recordGateBlocked(TaskDO task, String rule, long used, long limit) {
        if (securityEventRecorder == null) {
            return;
        }
        try {
            securityEventRecorder.record(
                    SecurityEventKind.GATE_BLOCKED,
                    SecurityDims.ofUser(
                            task.getPlatform(), task.getBotId(), task.getGroupId(), task.getCreatorUserId()),
                    java.util.Map.of("rule", rule, "used", used, "limit", limit),
                    currentTraceId());
        } catch (Exception e) {
            log.warn("重任务频控审计记录失败 rule={}", rule, e);
        }
    }

    private static String currentTraceId() {
        io.opentelemetry.api.trace.SpanContext ctx =
                io.opentelemetry.api.trace.Span.current().getSpanContext();
        if (ctx != null && ctx.isValid()) {
            return ctx.getTraceId();
        }
        return org.slf4j.MDC.get("traceId");
    }

    /**
     * 到期/条件唤醒推进（请求类，taskWake Effect 动作体）：
     * 时机评估通过 → SCHEDULED/WAITING → RUNNING + 分派；时机不合 → 推迟重排。
     */
    public void advanceTask(long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("taskWake 载荷指向不存在的 Task taskId={}", taskId);
            return;
        }
        if (TaskStatus.SCHEDULED.name().equals(task.getStatus())) {
            String timing = evaluateTiming(task);
            if (timing != null) {
                log.info("Task 时机不合推迟重排 taskId={} 原因={}", taskId, timing);
                taskMapper.updateScheduleAt(taskId, LocalDateTime.now(clock).plusMinutes(1));
                scheduleWake(taskId);
                return;
            }
            if (taskMapper.advanceStatus(taskId, TaskStatus.SCHEDULED.name(),
                    TaskStatus.RUNNING.name()) == 0) {
                return;   // 并发推进（崩溃恢复重放等）
            }
            emitTaskTransitioned(task, TaskStatus.SCHEDULED.name(), TaskStatus.RUNNING.name());
            dispatchByKind(task);
        } else if (TaskStatus.WAITING.name().equals(task.getStatus())) {
            // 条件唤醒：WAITING → RUNNING 继续推进
            if (taskMapper.advanceStatus(taskId, TaskStatus.WAITING.name(),
                    TaskStatus.RUNNING.name()) == 0) {
                return;
            }
            emitTaskTransitioned(task, TaskStatus.WAITING.name(), TaskStatus.RUNNING.name());
            dispatchByKind(task);
        }
    }

    /**
     * 人工停止（请求类）：RUNNING/WAITING/SCHEDULED → CANCELLED；活跃 Worker 的取消信号
     * 同步置位（经信号注册表——不 interrupt 不持线程引用，Worker 安静收尾不写结论）
     */
    public void stopTask(long taskId, String reason) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null || TaskStatus.valueOf(task.getStatus()).isTerminal()) {
            throw new CommandRejectedException("Task 不存在或已终态（" +
                    (task == null ? "不存在" : task.getStatus()) + "），无法停止");
        }
        taskMapper.advanceStatus(taskId, task.getStatus(), TaskStatus.CANCELLED.name());
        emitTaskTransitioned(task, task.getStatus(), TaskStatus.CANCELLED.name());
        workerSignalRegistry.cancelWorker(taskId);
        recordExecution(taskId, TaskExecutionLogDO.KIND_ROUND, "人工停止：" + reason);
    }

    /**
     * Worker 进度上报（事实）：只写执行记录不触发推理（负向断言点——期间无任何模型调用）
     */
    public void taskProgress(long taskId, String note) {
        recordExecution(taskId, TaskExecutionLogDO.KIND_PROGRESS, note);
    }

    /**
     * 结论回传（事实）：轻任务在此终结（DONE/FAILED + result 留痕）；重任务（Worker）结论
     * 同时登记内部交付事件（accept 直通）→ generation Effect 驱动常驻会话人格化交付。
     */
    public void taskConcluded(long taskId, String resultJson, String errorCode) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        boolean success = errorCode == null;
        if (!TaskStatus.RUNNING.name().equals(task.getStatus())) {
            log.warn("结论回传时 Task 非 RUNNING（幂等跳过） taskId={} status={}", taskId, task.getStatus());
            return;
        }
        taskMapper.advanceStatus(taskId, TaskStatus.RUNNING.name(),
                success ? TaskStatus.DONE.name() : TaskStatus.FAILED.name());
        emitTaskTransitioned(task, TaskStatus.RUNNING.name(),
                success ? TaskStatus.DONE.name() : TaskStatus.FAILED.name());
        taskMapper.updateResult(taskId, resultJson, errorCode, LocalDateTime.now(clock));

        // 重任务结论 → 内部交付事件（重新竞争群推理权，由常驻会话人格化交付）
        if (success && TaskDO.KIND_HEAVY.equals(task.getTaskKind())) {
            registerDeliveryEvent(task);
        }
    }

    /**
     * 策略变更重验证：存量失格（Mandate 失格或创建人进入黑名单）批量取消并记审计
     * （T078 策略收紧存量取消审计：detail 附被取消 Task id 清单）
     */
    public int revalidateMandates(String platform, String botId, String groupId) {
        java.util.List<Long> cancelledIds = new java.util.ArrayList<>();
        for (TaskDO task : taskMapper.selectByGroup(platform, botId, groupId)) {
            if (TaskStatus.valueOf(task.getStatus()).isTerminal()) {
                continue;
            }
            boolean disqualified = mandateGuard.isDisqualified(task)
                    || (gatePolicyService != null && task.getCreatorUserId() != null
                    && gatePolicyService.isBlacklisted(platform, botId, groupId, task.getCreatorUserId()));
            if (disqualified) {
                taskMapper.advanceStatus(task.getId(), task.getStatus(), TaskStatus.CANCELLED.name());
                emitTaskTransitioned(task, task.getStatus(), TaskStatus.CANCELLED.name());
                workerSignalRegistry.cancelWorker(task.getId());
                recordExecution(task.getId(), TaskExecutionLogDO.KIND_ROUND,
                        "Mandate/黑名单重验证失格取消（策略收紧）类别=" + task.getCategory());
                cancelledIds.add(task.getId());
            }
        }
        if (!cancelledIds.isEmpty() && securityEventRecorder != null) {
            try {
                securityEventRecorder.record(
                        cn.edu.scut.chatbot.security.audit.SecurityEventKind.ADMIN_OPERATE,
                        cn.edu.scut.chatbot.security.audit.SecurityDims.ofGroup(platform, botId, groupId),
                        java.util.Map.of("action", "policyTightenCancelTasks",
                                "cancelledTaskIds", cancelledIds,
                                "cancelledCount", cancelledIds.size()),
                        currentTraceId());
            } catch (Exception e) {
                log.warn("策略收紧存量取消审计写入失败 group={}", groupId, e);
            }
        }
        return cancelledIds.size();
    }

    /**
     * 重启恢复：RUNNING/WAITING 从最后检查点恢复且动作留痕
     * （RUNNING 的轻/重执行体已随进程消失——按 kind 重新分派；WAITING 保持等待待唤醒）
     */
    public int recoverOnStartup() {
        int recovered = 0;
        for (TaskDO task : taskMapper.selectRecoverable()) {
            if (TaskStatus.RUNNING.name().equals(task.getStatus())) {
                if (TaskDO.KIND_HEAVY.equals(task.getTaskKind())) {
                    // 重任务回 WAITING 重新唤醒 Worker（快照自包含，重跑无副作用积累）
                    taskMapper.advanceStatus(task.getId(), TaskStatus.RUNNING.name(),
                            TaskStatus.WAITING.name());
                }
                taskMapper.updateScheduleAt(task.getId(), LocalDateTime.now(clock));
                scheduleWake(task.getId());
                recordExecution(task.getId(), TaskExecutionLogDO.KIND_ROUND, "重启恢复：从检查点重新入队");
                recovered++;
            }
            // WAITING 保持：等条件唤醒（wake_condition 命中由 taskWake 驱动）
        }
        return recovered;
    }

    /**
     * 时机评估（两闸分离的「时机」闸）：群暂停不合、群活跃正在聊不合（避免打断）
     */
    private String evaluateTiming(TaskDO task) {
        GroupDO group = groupMapper.selectByGroup(task.getPlatform(), task.getBotId(), task.getGroupId());
        if (group == null || GroupDO.LIFECYCLE_PAUSED.equals(group.getLifecycle())) {
            return "群暂停中";
        }
        List<TriggerEventDO> recent = triggerEventMapper.selectAllByGroup(
                task.getPlatform(), task.getBotId(), task.getGroupId());
        LocalDateTime now = LocalDateTime.now(clock);
        boolean topicHot = recent.stream()
                .anyMatch(trigger -> trigger.getOccurredAt() != null
                        && Duration.between(trigger.getOccurredAt(), now).toMillis()
                        < TOPIC_QUIET_REQUIRED_MILLIS);
        if (topicHot) {
            return "群聊正热（3 秒内有新消息），推迟打扰";
        }
        return null;
    }

    private void dispatchByKind(TaskDO task) {
        if (TaskDO.KIND_HEAVY.equals(task.getTaskKind())) {
            enqueueEffect(task, EffectsQueueDO.KIND_WORKER_LAUNCH,
                    "{\"taskId\":" + task.getId() + "}");
        } else {
            enqueueEffect(task, EffectsQueueDO.KIND_GENERATION, "{\"form\":\"lightTask\"}");
        }
    }

    /**
     * 内部交付事件登记：dedupKey 业务键（taskDelivery:{taskId}）、accept 直通不过闸门
     * （授权在任务创建时已确立，spec US4——主动行为不设旁路指内部触发走同一闸门，
     * 交付事件的授权来自 Task Mandate 本身）
     */
    private void registerDeliveryEvent(TaskDO task) {
        TriggerEventDO delivery = new TriggerEventDO();
        delivery.setId(idGenerator.nextId());
        delivery.setPlatform(task.getPlatform());
        delivery.setBotId(task.getBotId());
        delivery.setGroupId(task.getGroupId());
        delivery.setTriggerKind(TriggerEventDO.KIND_INTERNAL);
        delivery.setEventType("taskDelivery");
        delivery.setDedupKey("taskDelivery:" + task.getId());
        delivery.setDigest("任务 " + task.getId() + " 结论待交付");
        delivery.setSourceRef(String.valueOf(task.getId()));
        delivery.setDecision(TriggerEventDO.DECISION_ACCEPT);   // 直通不过闸门
        delivery.setDecisionLayer(TriggerEventDO.LAYER_L0);
        delivery.setDecisionReason("任务结论交付（授权在创建时已确立）");
        delivery.setOccurredAt(LocalDateTime.now(clock));
        delivery.setDecidedAt(LocalDateTime.now(clock));
        try {
            triggerEventMapper.insertTrigger(delivery);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return;   // 重复登记（重放）幂等
        }
        enqueueEffect(task, EffectsQueueDO.KIND_GENERATION, "{\"form\":\"delivery\"}");
    }

    private void scheduleWake(long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null || TaskStatus.valueOf(task.getStatus()).isTerminal()) {
            return;
        }
        enqueueEffect(task, EffectsQueueDO.KIND_TASK_WAKE, "{\"taskId\":" + taskId + "}");
    }

    private void enqueueEffect(TaskDO task, String kind, String payload) {
        EffectsQueueDO effect = new EffectsQueueDO();
        effect.setPlatform(task.getPlatform());
        effect.setBotId(task.getBotId());
        effect.setGroupId(task.getGroupId());
        effect.setKind(kind);
        effect.setPayload(payload);
        effect.setNextRunAt(task.getScheduleAt() != null ? task.getScheduleAt()
                : LocalDateTime.now(clock));
        effectsQueueMapper.enqueueMerge(effect);
    }

    private void recordExecution(long taskId, String kind, String note) {
        TaskExecutionLogDO logEntry = new TaskExecutionLogDO();
        logEntry.setTaskId(taskId);
        logEntry.setRecordKind(kind);
        logEntry.setNote(note);
        taskMapper.insertExecutionLog(logEntry);
    }
}
