package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.infrastructure.eventbus.BoundedEventListener;
import cn.edu.scut.chatbot.loop.AgentContext;
import cn.edu.scut.chatbot.loop.AgentLoop;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.CancellationSignal;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.loop.StreamFn;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.SystemNote;
import cn.edu.scut.chatbot.loop.transcript.SpeechOutcomeJudger;
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector;
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
 * <p>Worker Run 形态：全量工具无 sendMessage（结构锁死——装配即边界）、任务快照上下文
 * （不读群聊会话历史）、worker 形态 RunConfig（高轮次预算 + 硬顶超限优雅收敛标记超时）；
 * 中间过程只写 task_execution_log 与 task_output_archive，不进群聊会话历史；
 * 结论经 taskConcluded 回传（内部交付事件重新竞争群推理权）。
 */
@Component
public class WorkerRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    /**
     * Worker 任务执行提示词（T086 PromptAssembler 的任务层就位前的占位——
     * 能力协议层无发言协议 + 任务执行提示词的形态骨架）
     */
    private static final String WORKER_PROMPT =
            "你是任务执行 Worker。你的唯一目标是完成下面给定的任务并产出结论。\n"
                    + "你可以使用提供的全部工具（注意：你没有 sendMessage——你不能对群里说话，"
                    + "结论由主会话代为交付）。\n"
                    + "多轮推进直到任务完成或达到预算上限；预算超限会被优雅收敛，"
                    + "届时尽量给出已完成的阶段性结论。";

    private final AgentLoop agentLoop;
    private final StreamFn streamFn;
    private final TaskCoordinator taskCoordinator;
    private final TaskMapper taskMapper;
    private final cn.edu.scut.chatbot.prompt.PromptAssembler promptAssembler;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final Map<String, AgentTool> availableTools;

    public WorkerRunner(AgentLoop agentLoop,
                        StreamFn streamFn,
                        TaskCoordinator taskCoordinator,
                        TaskMapper taskMapper,
                        cn.edu.scut.chatbot.prompt.PromptAssembler promptAssembler,
                        IdGenerator idGenerator,
                        Clock clock,
                        List<AgentTool> tools) {
        this.agentLoop = agentLoop;
        this.streamFn = streamFn;
        this.taskCoordinator = taskCoordinator;
        this.taskMapper = taskMapper;
        this.promptAssembler = promptAssembler;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.availableTools = new HashMap<>();
        for (AgentTool tool : tools) {
            availableTools.put(tool.descriptor().name(), tool);
        }
    }

    /**
     * 驱动 Worker Run（WorkerLauncher 在专属虚拟线程上调用；阻塞至 Run 终态）
     */
    public void run(long taskId, CancellationSignal signal) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        // Worker 工具集：全量但无 sendMessage（结构锁死非自觉）
        List<AgentTool> workerTools = new ArrayList<>();
        for (AgentTool tool : availableTools.values()) {
            if (!SpeechOutcomeJudger.SEND_MESSAGE_TOOL.equals(tool.descriptor().name())) {
                workerTools.add(tool);
            }
        }

        // 任务快照上下文（不读群聊）：快照内容以 SystemNote 形态注入（自包含输入）
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> inputs = new ArrayList<>();
        inputs.add(new SystemNote(Long.toUnsignedString(idGenerator.nextId()), clock.instant(),
                50, "task-" + taskId, "taskSnapshot", "[任务快照] " + task.getSnapshot()));

        long runId = idGenerator.nextId();
        // Worker 形态提示词：能力协议（无发言协议）+ 任务执行提示词（T086 组装器挂接）
        String workerPrompt = promptAssembler.assemble(RunConfig.ExecutionForm.WORKER,
                task.getPlatform(), task.getBotId(), task.getGroupId(),
                "完成下面给定的任务并产出结论。你可以使用提供的全部工具（注意：你没有 sendMessage"
                        + "——你不能对群里说话，结论由主会话代为交付）。多轮推进直到任务完成或达到"
                        + "预算上限；预算超限会被优雅收敛，届时尽量给出已完成的阶段性结论。");
        AgentContext context = new AgentContext(workerPrompt.isBlank() ? WORKER_PROMPT : workerPrompt, inputs,
                workerTools.stream().map(AgentTool::descriptor).toList(),
                GenerationParams.defaults());

        AgentLoop.RunSpec spec = new AgentLoop.RunSpec(
                runId, task.getPlatform(), task.getBotId(), task.getGroupId(),
                null, taskId, context, RunConfig.workerDefaults(),
                LoopHooks.builder(new TranscriptProjector()).build(),
                toolsByName(workerTools), streamFn, clock, idGenerator::nextId,
                (completedRunId, turnMessages) -> {
                    // 中间过程只写任务执行记录（round 留痕），不进群聊会话历史
                    taskCoordinator.taskProgress(taskId, "轮次完成：" + turnMessages.size() + " 条消息");
                },
                new BoundedEventListener());

        AgentLoop.RunOutcome outcome = agentLoop.execute(spec);

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

    private Map<String, AgentTool> toolsByName(List<AgentTool> tools) {
        Map<String, AgentTool> byName = new HashMap<>();
        for (AgentTool tool : tools) {
            byName.put(tool.descriptor().name(), tool);
        }
        return byName;
    }
}
