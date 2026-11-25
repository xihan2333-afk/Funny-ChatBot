package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.infrastructure.eventbus.BoundedEventListener;
import cn.edu.scut.chatbot.loop.*;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.SystemNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker Runner（spec US4、research R7）：重任务的隔离执行体。
 *
 * <p>Worker Run 形态：按归属白名单裁配的工具集（设计 2.2.3.7 表格——重活工具在列，
 * sendMessage 等面向群聊的工具不在）、任务快照上下文（不读群聊会话历史）、
 * worker 形态 RunConfig（高轮次预算 + 硬顶超限优雅收敛标记超时）；
 * 中间过程只写 task_execution_log 与 task_output_archive，不进群聊会话历史；
 * 结论经 taskConcluded 回传（内部交付事件重新竞争群推理权）。
 */
@Component
public class WorkerRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    /**
     * resolveCredentials 钩子实现：按 task_worker 槽位绑定的供应商取下一把可用 key——
     * 全部不可用返回 null（无凭证按错误结果处理，Run 失败进 Effects 重试）
     */
    private String resolveCredentialFor(AgentContext context) {
        if (context.modelConfig() == null || context.modelConfig().model() == null) {
            return null;
        }
        return credentialPool.nextAvailable(context.modelConfig().model().providerCode());
    }

    /**
     * Worker 任务执行提示词（T086 PromptAssembler 的任务层就位前的占位——
     * 能力协议层无发言协议 + 任务执行提示词的形态骨架）
     */
    private static final String WORKER_PROMPT =
            "你是任务执行 Worker。你的唯一目标是完成下面给定的任务并产出结论。\n"
                    + "你可以使用提供的工具（生图、语音、网页抓取、代码执行、读取完整输出等"
                    + "重活类工具；注意：你没有 sendMessage——你不能对群里说话，"
                    + "结论由主会话代为交付）。\n"
                    + "多轮推进直到任务完成或达到预算上限；预算超限会被优雅收敛，"
                    + "届时尽量给出已完成的阶段性结论。";

    private final AgentLoop agentLoop;
    private final StreamFn streamFn;
    private final TaskCoordinator taskCoordinator;
    private final TaskMapper taskMapper;
    private final cn.edu.scut.chatbot.prompt.PromptAssembler promptAssembler;
    private final cn.edu.scut.chatbot.tool.assembler.ToolsetAssembler toolsetAssembler;
    private final cn.edu.scut.chatbot.context.ContextAwareProjector contextAwareProjector;
    private final cn.edu.scut.chatbot.tool.ToolCallGuard toolCallGuard;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final cn.edu.scut.chatbot.llm.slot.SlotResolver slotResolver;
    private final cn.edu.scut.chatbot.llm.slot.CredentialPool credentialPool;
    private final cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder;

    public WorkerRunner(AgentLoop agentLoop,
                        StreamFn streamFn,
                        TaskCoordinator taskCoordinator,
                        TaskMapper taskMapper,
                        cn.edu.scut.chatbot.prompt.PromptAssembler promptAssembler,
                        cn.edu.scut.chatbot.tool.assembler.ToolsetAssembler toolsetAssembler,
                        cn.edu.scut.chatbot.context.ContextAwareProjector contextAwareProjector,
                        cn.edu.scut.chatbot.tool.ToolCallGuard toolCallGuard,
                        IdGenerator idGenerator,
                        Clock clock,
                        cn.edu.scut.chatbot.llm.slot.SlotResolver slotResolver,
                        cn.edu.scut.chatbot.llm.slot.CredentialPool credentialPool,
                        cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder) {
        this.agentLoop = agentLoop;
        this.streamFn = streamFn;
        this.taskCoordinator = taskCoordinator;
        this.taskMapper = taskMapper;
        this.promptAssembler = promptAssembler;
        this.toolsetAssembler = toolsetAssembler;
        this.contextAwareProjector = contextAwareProjector;
        this.toolCallGuard = toolCallGuard;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.slotResolver = slotResolver;
        this.credentialPool = credentialPool;
        this.costRecorder = costRecorder;
    }

    /**
     * 驱动 Worker Run（WorkerLauncher 在专属虚拟线程上调用；阻塞至 Run 终态）
     */
    public void run(long taskId, CancellationSignal signal) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        // Worker 工具集：按归属白名单裁配（装配器第四步——sendMessage 等读群类工具不在其列）
        List<AgentTool> workerTools = toolsetAssembler.assemble(RunConfig.ExecutionForm.WORKER,
                task.getPlatform(), task.getBotId(), task.getGroupId());

        // 任务快照上下文（不读群聊）：快照内容以 SystemNote 形态注入（自包含输入）
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> inputs = new ArrayList<>();
        inputs.add(new SystemNote(Long.toUnsignedString(idGenerator.nextId()), clock.instant(),
                50, "task-" + taskId, "taskSnapshot", "[任务快照] " + task.getSnapshot()));

        // 一个 Run 一个监听器（Run 结束关闭——后台消费线程不泄漏）
        BoundedEventListener eventListener = new BoundedEventListener();
        long runId = idGenerator.nextId();
        // Worker 形态提示词：能力协议（无发言协议）+ 任务执行提示词（T086 组装器挂接）
        String workerPrompt = promptAssembler.assemble(RunConfig.ExecutionForm.WORKER,
                task.getPlatform(), task.getBotId(), task.getGroupId(),
                "完成下面给定的任务并产出结论。你可以使用提供的重活类工具（生图、语音、网页抓取、"
                        + "代码执行、读取完整输出；注意：你没有 sendMessage——你不能对群里说话，"
                        + "结论由主会话代为交付）。多轮推进直到任务完成或达到"
                        + "预算上限；预算超限会被优雅收敛，届时尽量给出已完成的阶段性结论。");
        cn.edu.scut.chatbot.loop.ModelConfig modelConfig;
        try {
            // Worker 形态绑 task_worker 槽位（推理模型取向——与 chat_main 分开配）
            modelConfig = slotResolver.resolve(cn.edu.scut.chatbot.llm.slot.ModelCatalog.SLOT_TASK_WORKER);
        } catch (cn.edu.scut.chatbot.llm.slot.ModelCatalog.SlotResolutionException e) {
            log.error("task_worker 槽位解析失败（Worker 启动失败） errorCode=llm.config_missing traceId=n/a：{}",
                    e.getMessage());
            concludeFailed(taskId, "llm.config_missing:" + e.getMessage());
            return;
        }
        AgentContext context = new AgentContext(workerPrompt.isBlank() ? WORKER_PROMPT : workerPrompt, inputs,
                workerTools.stream().map(AgentTool::descriptor).toList(),
                modelConfig);

        AgentLoop.RunSpec spec = new AgentLoop.RunSpec(
                runId, task.getPlatform(), task.getBotId(), task.getGroupId(),
                null, taskId, context, RunConfig.workerDefaults(),
                LoopHooks.builder(contextAwareProjector)
                        // Worker 形态现注：当前时间（任务快照已在启动上下文里，回执无群可投）
                        .transformContext(original -> new AgentContext(original.systemPrompt(),
                                withCurrentTime(original.messages()), original.tools(),
                                original.modelConfig()))
                        .resolveCredentials(() -> resolveCredentialFor(context))
                        .beforeToolCall(toolCallGuard.forRun(task.getPlatform(),
                                task.getBotId(), task.getGroupId()))
                        .build(),
                toolsByName(workerTools),
                new cn.edu.scut.chatbot.engine.CostAwareStreamFn(streamFn, costRecorder,
                        new cn.edu.scut.chatbot.llm.gateway.LlmCallMeta(task.getPlatform(),
                                task.getBotId(), task.getGroupId(), runId, "worker",
                                "run-" + runId, false)),
                clock, idGenerator::nextId,
                (completedRunId, turnMessages) -> {
                    // 中间过程只写任务执行记录（round 留痕），不进群聊会话历史
                    taskCoordinator.taskProgress(taskId, "轮次完成：" + turnMessages.size() + " 条消息");
                },
                eventListener);

        AgentLoop.RunOutcome outcome;
        try {
            outcome = agentLoop.execute(spec);
        } finally {
            eventListener.close();
        }

        // 取消（群暂停/任务取消/群销毁）：安静收尾不写结论（Task 保持 RUNNING 由恢复机制处理）
        if (signal.isCancelled() || AgentLoop.RunOutcome.STOP_ABORTED.equals(outcome.stopReason())) {
            log.info("Worker 被取消安静收尾 taskId={}", taskId);
            return;
        }
        // 结论组装：预算超限优雅收敛标记超时（不硬杀——产出已有结论）
        String conclusion = buildConclusion(task, outcome);
        boolean timeout = AgentLoop.RunOutcome.STOP_BUDGET_EXCEEDED.equals(outcome.stopReason());
        taskCoordinator.taskConcluded(taskId,
                "{\"conclusion\":\"" + sanitize(conclusion) + "\",\"timedOut\":" + timeout + "}",
                null);
    }

    /**
     * Worker 异常时的失败结论回传（WorkerLauncher 的兜底调用）
     */
    public void concludeFailed(long taskId, String errorCode) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task != null && TaskStatus.RUNNING.name().equals(task.getStatus())) {
            taskCoordinator.taskConcluded(taskId, null, errorCode);
        }
    }

    private String buildConclusion(TaskDO task, AgentLoop.RunOutcome outcome) {
        if (outcome.fallbackText() != null && !outcome.silent()) {
            return outcome.fallbackText();
        }
        if (!outcome.newMessages().isEmpty()) {
            return "任务处理完成（详见执行记录 runId 关联条目）";
        }
        return "任务无产出结论";
    }

    private String sanitize(String text) {
        return text == null ? "" : text.replace("\"", "'").replace("\n", " ");
    }

    /**
     * 现注区注入副本（transformContext 三纪律：改动只作用于副本）
     */
    private List<cn.edu.scut.chatbot.loop.message.AgentMessage> withCurrentTime(
            List<cn.edu.scut.chatbot.loop.message.AgentMessage> messages) {
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> augmented =
                new ArrayList<>(messages);
        augmented.add(new SystemNote(Long.toUnsignedString(idGenerator.nextId()),
                clock.instant(), 50, "task-runtime", "runtime-note",
                "[系统现注] 当前时间：" + clock.instant()));
        return augmented;
    }

    private Map<String, AgentTool> toolsByName(List<AgentTool> tools) {
        Map<String, AgentTool> byName = new HashMap<>();
        for (AgentTool tool : tools) {
            byName.put(tool.descriptor().name(), tool);
        }
        return byName;
    }
}
