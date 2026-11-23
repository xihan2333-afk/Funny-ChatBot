package cn.edu.scut.chatbot.engine.turn;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.engine.task.TaskStatus;
import cn.edu.scut.chatbot.infrastructure.eventbus.BoundedEventListener;
import cn.edu.scut.chatbot.loop.AgentContext;
import cn.edu.scut.chatbot.loop.AgentLoop;
import cn.edu.scut.chatbot.loop.AgentTool;
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
 * 生成驱动器（T058/T064/T065 交付路径）：engine 驱动 Run 的组装点——
 * turn 形态（被动回应）、lightTask 形态（轻任务到期发言）、delivery 形态
 * （Worker 结论人格化交付）。AgentContext 的 systemPrompt 经 PromptAssembler 四层组装
 * （T086 挂接）、投影经 ContextAwareProjector 完整四操作（T098 挂接）。
 */
@Component
public class GenerationRunner {

    private static final Logger log = LoggerFactory.getLogger(GenerationRunner.class);

    /**
     * systemPrompt 组装（T086 已挂接 PromptAssembler 四层组装；本常量仅作组装失败的兜底）
     */
    private static final String FALLBACK_SYSTEM_PROMPT =
            "你是群聊 AI 伙伴。想说话只能调用 sendMessage 工具，简短口语化，拿不准就沉默。";

    private String systemPromptFor(RunConfig.ExecutionForm form, String platform,
                                   String botId, String groupId, String taskLayerText) {
        try {
            String assembled = promptAssembler.assemble(form, platform, botId, groupId, taskLayerText);
            return assembled.isBlank() ? FALLBACK_SYSTEM_PROMPT : assembled;
        } catch (Exception e) {
            log.warn("systemPrompt 组装异常走兜底占位", e);
            return FALLBACK_SYSTEM_PROMPT;
        }
    }

    private final AgentLoop agentLoop;
    private final StreamFn streamFn;
    private final SessionManager sessionManager;
    private final TurnCoordinator turnCoordinator;
    private final TurnMapper turnMapper;
    private final TaskCoordinator taskCoordinator;
    private final TaskMapper taskMapper;
    private final GroupMapper groupMapper;
    private final cn.edu.scut.chatbot.engine.outbox.OutboxWriter outboxWriter;
    private final cn.edu.scut.chatbot.prompt.PromptAssembler promptAssembler;
    private final cn.edu.scut.chatbot.context.ContextAwareProjector contextAwareProjector;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final BoundedEventListener eventListener;
    private final Map<String, AgentTool> availableTools;

    public GenerationRunner(AgentLoop agentLoop,
                            StreamFn streamFn,
                            SessionManager sessionManager,
                            TurnCoordinator turnCoordinator,
                            TurnMapper turnMapper,
                            TaskCoordinator taskCoordinator,
                            TaskMapper taskMapper,
                            GroupMapper groupMapper,
                            cn.edu.scut.chatbot.engine.outbox.OutboxWriter outboxWriter,
                            cn.edu.scut.chatbot.prompt.PromptAssembler promptAssembler,
                            cn.edu.scut.chatbot.context.ContextAwareProjector contextAwareProjector,
                            IdGenerator idGenerator,
                            Clock clock,
                            List<AgentTool> tools) {
        this.agentLoop = agentLoop;
        this.streamFn = streamFn;
        this.sessionManager = sessionManager;
        this.turnCoordinator = turnCoordinator;
        this.turnMapper = turnMapper;
        this.taskCoordinator = taskCoordinator;
        this.taskMapper = taskMapper;
        this.groupMapper = groupMapper;
        this.outboxWriter = outboxWriter;
        this.promptAssembler = promptAssembler;
        this.contextAwareProjector = contextAwareProjector;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.eventListener = new BoundedEventListener();
        this.availableTools = new HashMap<>();
        for (AgentTool tool : tools) {
            availableTools.put(tool.descriptor().name(), tool);
        }
    }

    /**
     * 驱动一次 Turn 生成（generation Effect 处理器在获得推理权后调用；阻塞至 Run 终态）。
     */
    public void runTurn(long turnId) {
        TurnDO turn = turnMapper.selectById(turnId);
        if (turn == null || !TurnStatus.GENERATING.name().equals(turn.getStatus())) {
            return;
        }
        SessionManager.InferenceTicket ticket = sessionManager.acquireInference(
                turn.getPlatform(), turn.getBotId(), turn.getGroupId(),
                SessionManager.PURPOSE_TURN);
        try {
            AgentSessionDO session = sessionManager.getOrCreateSession(
                    turn.getPlatform(), turn.getBotId(), turn.getGroupId());
            List<cn.edu.scut.chatbot.loop.message.AgentMessage> history =
                    sessionManager.loadTranscript(session);
            AgentContext context = new AgentContext(systemPromptFor(RunConfig.ExecutionForm.TURN, turn.getPlatform(), turn.getBotId(), turn.getGroupId(), null), history,
                    assembleTurnTools().stream().map(AgentTool::descriptor).toList(),
                    GenerationParams.defaults());
            AgentLoop.RunOutcome outcome = executeSpec(turn.getPlatform(), turn.getBotId(),
                    turn.getGroupId(), turn.getId(), null, context, RunConfig.turnDefaults(),
                    session, assembleTurnTools());
            settleTurnOutcome(turn, outcome);
        } finally {
            sessionManager.releaseInference(ticket);
        }
    }

    /**
     * 轻任务执行（lightTask 形态）：常驻会话直接单轮生成——任务指令以现注注入、
     * 同 Turn 工具集；结论写 result、置 DONE（生成发言经 sendMessage 出口自然投递）。
     */
    public void runLightTask(long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        SessionManager.InferenceTicket ticket = sessionManager.acquireInference(
                task.getPlatform(), task.getBotId(), task.getGroupId(),
                SessionManager.PURPOSE_LIGHT_TASK);
        try {
            AgentSessionDO session = sessionManager.getOrCreateSession(
                    task.getPlatform(), task.getBotId(), task.getGroupId());
            List<cn.edu.scut.chatbot.loop.message.AgentMessage> history =
                    sessionManager.loadTranscript(session);
            history.add(new SystemNote(Long.toUnsignedString(idGenerator.nextId()),
                    clock.instant(), 50, "task-" + taskId, "taskSnapshot",
                    "[任务指令] " + task.getSnapshot()));

            AgentContext context = new AgentContext(
                    systemPromptFor(RunConfig.ExecutionForm.LIGHT_TASK, task.getPlatform(),
                            task.getBotId(), task.getGroupId(), "按任务指令自然地完成定时发言"),
                    history, assembleTurnTools().stream().map(AgentTool::descriptor).toList(),
                    GenerationParams.defaults());
            AgentLoop.RunOutcome outcome = executeSpec(task.getPlatform(), task.getBotId(),
                    task.getGroupId(), null, taskId, context, RunConfig.lightTaskDefaults(),
                    session, assembleTurnTools());
            if (AgentLoop.RunOutcome.STOP_ERROR.equals(outcome.stopReason())
                    || AgentLoop.RunOutcome.STOP_ABORTED.equals(outcome.stopReason())) {
                taskCoordinator.taskConcluded(taskId, null,
                        "engine.task.light_failed:" + outcome.stopReason());
                return;
            }
            taskCoordinator.taskConcluded(taskId,
                    "{\"spoke\":" + (!outcome.silent()) + ",\"stopReason\":\""
                            + outcome.stopReason() + "\"}",
                    null);
        } finally {
            sessionManager.releaseInference(ticket);
        }
    }

    /**
     * Worker 结论交付（delivery 形态）：内部交付事件重新竞争推理权（交付类优先提升），
     * 常驻会话人格化交付。
     *
     * <p>交付前时效检查（spec US4 场景 5）：群暂停 → 安静收尾；发起人在群 → 成员数据源
     * 归画像系统（R10 桩面"暂无"，第一版不拦）；话题未翻篇 → 结论现注里带时效提示、
     * 由模型结合近期上下文判断（不硬拦——机械拦截会把"迟到但有价值"的结论误杀）。
     */
    public void runDelivery(long taskId) {
        TaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        GroupDO group = groupMapper.selectByGroup(task.getPlatform(), task.getBotId(), task.getGroupId());
        if (group != null && GroupDO.LIFECYCLE_PAUSED.equals(group.getLifecycle())) {
            log.info("交付时群已暂停，安静收尾（结论已留痕 Task） taskId={}", taskId);
            return;
        }
        SessionManager.InferenceTicket ticket = sessionManager.acquireInference(
                task.getPlatform(), task.getBotId(), task.getGroupId(),
                SessionManager.PURPOSE_DELIVERY);
        try {
            AgentSessionDO session = sessionManager.getOrCreateSession(
                    task.getPlatform(), task.getBotId(), task.getGroupId());
            List<cn.edu.scut.chatbot.loop.message.AgentMessage> history =
                    sessionManager.loadTranscript(session);
            history.add(new SystemNote(Long.toUnsignedString(idGenerator.nextId()),
                    clock.instant(), 50, "task-" + taskId, "taskDelivery",
                    "[系统提示] 你之前接受的任务已完成，结论如下。如果群里话题早已翻篇或结论"
                            + "已无意义，就安静收尾不说话；否则以你的口吻转告（不要机械复读）："
                            + task.getResult()));

            AgentContext context = new AgentContext(systemPromptFor(RunConfig.ExecutionForm.TURN,
                    task.getPlatform(), task.getBotId(), task.getGroupId(), "任务结论人格化交付"), history,
                    assembleTurnTools().stream().map(AgentTool::descriptor).toList(),
                    GenerationParams.defaults());
            executeSpec(task.getPlatform(), task.getBotId(), task.getGroupId(), null, taskId,
                    context, RunConfig.turnDefaults(), session, assembleTurnTools());
        } finally {
            sessionManager.releaseInference(ticket);
        }
    }

    /**
     * Turn 三结局推进：发言清单 → SETTLING；兜底文本经 sendMessage 补投（发言出口唯一纪律）；
     * 沉默 → SILENT；error/aborted → FAILED
     */
    private void settleTurnOutcome(TurnDO turn, AgentLoop.RunOutcome outcome) {
        List<Long> outboxIds = new ArrayList<>();
        for (String id : outcome.speechOutboxIds()) {
            outboxIds.add(Long.parseLong(id));
        }
        if (outcome.fallbackText() != null && !outcome.silent()) {
            long outboxId = sendFallback(turn, outcome.fallbackText());
            if (outboxId > 0) {
                outboxIds.add(outboxId);
            }
        }
        if (AgentLoop.RunOutcome.STOP_ERROR.equals(outcome.stopReason())
                || AgentLoop.RunOutcome.STOP_ABORTED.equals(outcome.stopReason())) {
            turnCoordinator.generationFailed(turn.getId(),
                    "engine.turn.generation_failed:" + outcome.stopReason());
            return;
        }
        turnCoordinator.generationCompleted(turn.getId(), outboxIds);
    }

    /**
     * Run 执行公共体（Turn/轻任务/交付共用组装路径）
     */
    private AgentLoop.RunOutcome executeSpec(String platform, String botId, String groupId,
                                             Long turnId, Long taskId, AgentContext context,
                                             RunConfig config, AgentSessionDO session,
                                             List<AgentTool> tools) {
        AgentLoop.RunSpec spec = new AgentLoop.RunSpec(
                idGenerator.nextId(), platform, botId, groupId, turnId, taskId, context, config,
                LoopHooks.builder(contextAwareProjector)
                        .transformContext(original -> injectCurrentNotes(original, session))
                        .build(),
                toolsByName(tools), streamFn, clock, idGenerator::nextId,
                (completedRunId, turnMessages) ->
                        sessionManager.appendTranscript(session, turnMessages, completedRunId),
                eventListener);
        return agentLoop.execute(spec);
    }

    /**
     * transformContext 现注区注入（任务清单当前态——US4 就位后接真实清单）
     */
    private AgentContext injectCurrentNotes(AgentContext original, AgentSessionDO session) {
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> augmented =
                new ArrayList<>(original.messages());
        augmented.add(new SystemNote(
                Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 50, null,
                "runtime-note", "[系统现注] 当前时间：" + clock.instant()
                + "；进行中任务：" + runningTaskSummary(session)));
        return new AgentContext(original.systemPrompt(), augmented,
                original.tools(), original.params());
    }

    private String runningTaskSummary(AgentSessionDO session) {
        List<TaskDO> tasks = taskMapper.selectByGroup(session.getPlatform(),
                session.getBotId(), session.getGroupId());
        List<String> running = new ArrayList<>();
        for (TaskDO task : tasks) {
            if (TaskStatus.RUNNING.name().equals(task.getStatus())
                    || TaskStatus.WAITING.name().equals(task.getStatus())) {
                running.add("#" + task.getId() + " " + task.getCategory()
                        + "（" + task.getStatus() + "）");
            }
        }
        return running.isEmpty() ? "暂无" : String.join("、", running);
    }

    /**
     * 兜底发言补投：经 sendMessage 唯一出口落 outbox（裸文本只是腹稿——投递必经出口）
     */
    private long sendFallback(TurnDO turn, String fallbackText) {
        try {
            String payloadJson = cn.edu.scut.chatbot.bootstrap.JsonMappers.valueToStringOrNull(
                    new cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload(
                            List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                                    cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT, fallbackText)),
                            null));
            return outboxWriter.enqueueOutbound(
                    turn.getPlatform(), turn.getBotId(), turn.getGroupId(),
                    cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                    payloadJson, turn.getId(), null);
        } catch (Exception e) {
            log.error("兜底发言补投失败（发言丢失，Turn 仍按无出事件收尾） turnId={}", turn.getId(), e);
            return -1;
        }
    }

    /**
     * turn/lightTask/delivery 形态工具集：快工具 + sendMessage
     * （Worker 形态的全量无 sendMessage 裁配在 WorkerRunner——T065）
     */
    private List<AgentTool> assembleTurnTools() {
        List<AgentTool> turnTools = new ArrayList<>();
        for (AgentTool tool : availableTools.values()) {
            if (SpeechOutcomeJudger.SEND_MESSAGE_TOOL.equals(tool.descriptor().name())) {
                turnTools.add(tool);
            } else if (tool.descriptor().speed() == cn.edu.scut.chatbot.loop.ToolDescriptor.ToolSpeed.FAST) {
                turnTools.add(tool);
            }
        }
        return turnTools;
    }

    private Map<String, AgentTool> toolsByName(List<AgentTool> tools) {
        Map<String, AgentTool> byName = new HashMap<>();
        for (AgentTool tool : tools) {
            byName.put(tool.descriptor().name(), tool);
        }
        return byName;
    }
}
