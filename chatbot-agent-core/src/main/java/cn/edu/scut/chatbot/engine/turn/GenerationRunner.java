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
import cn.edu.scut.chatbot.infrastructure.eventbus.LoopEventWiring;
import cn.edu.scut.chatbot.loop.*;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.SystemNote;
import cn.edu.scut.chatbot.security.delimit.DelimitGuard;
import cn.edu.scut.chatbot.security.delimit.DelimitHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * Agent 业务过程日志（agent. 前缀——生成驱动是业务流水，T022）
     */
    private static final Logger log = LoggerFactory.getLogger("agent.generation");

    /**
     * systemPrompt 组装（T086 已挂接 PromptAssembler 四层组装；本常量仅作组装失败的兜底）
     */
    private static final String FALLBACK_SYSTEM_PROMPT =
            "你是群聊 AI 伙伴。想说话只能调用 sendMessage 工具，简短口语化，拿不准就沉默。";

    private String systemPromptFor(RunConfig.ExecutionForm form, String platform,
                                   String botId, String groupId, String taskLayerText) {
        return assembleOutcomeFor(form, platform, botId, groupId, taskLayerText).prompt();
    }

    /**
     * 组装并带出版本信息（观测版本三键的 promptFragment / persona 两键来源；
     * slotBinding 键由槽位门面另取）。组装失败走兜底占位、版本全 0——Run 不因观测缺数据失败
     */
    private cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome assembleOutcomeFor(
            RunConfig.ExecutionForm form, String platform,
            String botId, String groupId, String taskLayerText) {
        try {
            cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome outcome =
                    promptAssembler.assembleDetailed(form, platform, botId, groupId, taskLayerText);
            return outcome.prompt().isBlank()
                    ? new cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome(
                    FALLBACK_SYSTEM_PROMPT, outcome.fragmentVersion(), outcome.personaVersion())
                    : outcome;
        } catch (Exception e) {
            log.warn("systemPrompt 组装异常走兜底占位", e);
            return new cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome(
                    FALLBACK_SYSTEM_PROMPT, 0, 0);
        }
    }

    /**
     * 版本三键合成（RunEvent START 携带——重放批次按版本分组聚合的口径）
     */
    private RunConfig.VersionKeys versionKeysOf(
            cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome outcome, String slotCode) {
        return new RunConfig.VersionKeys(outcome.fragmentVersion(), outcome.personaVersion(),
                slotResolver.bindingVersionOf(slotCode));
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
    private final cn.edu.scut.chatbot.tool.assembler.ToolsetAssembler toolsetAssembler;
    private final cn.edu.scut.chatbot.context.compaction.TranscriptCompactor transcriptCompactor;
    private final cn.edu.scut.chatbot.context.window.WindowAssembler windowAssembler;
    private final cn.edu.scut.chatbot.tool.ToolCallGuard toolCallGuard;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final BoundedEventListener eventListener;
    private final cn.edu.scut.chatbot.llm.slot.SlotResolver slotResolver;
    private final cn.edu.scut.chatbot.llm.slot.CredentialPool credentialPool;
    private final cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder;

    /**
     * 态度行渲染（T031 现注区注入）——setter 注入：构造器已 21 参不再扩（22 参构造器链禁令），
     * 未装配时零注入、引擎主路径不受影响
     */
    private cn.edu.scut.chatbot.memory.profile.AttitudeNoteRenderer attitudeNoteRenderer;

    @Autowired(required = false)
    void setAttitudeNoteRenderer(cn.edu.scut.chatbot.memory.profile.AttitudeNoteRenderer renderer) {
        this.attitudeNoteRenderer = renderer;
    }

    /**
     * 情绪服务（T047 现注区情绪行）——setter 注入同上
     */
    private cn.edu.scut.chatbot.memory.emotion.EmotionService emotionService;

    @Autowired(required = false)
    void setEmotionService(cn.edu.scut.chatbot.memory.emotion.EmotionService service) {
        this.emotionService = service;
    }

    /**
     * 定界 + 注入检测组合件（US7 不可信变量通道——现注区态度行）——setter 注入同上：
     * 未装配时回退静态定界，只丢检测不丢定界
     */
    private DelimitGuard delimitGuard;

    @Autowired(required = false)
    void setDelimitGuard(DelimitGuard guard) {
        this.delimitGuard = guard;
    }

    /**
     * 循环事件装配工厂（观测桥经它把消费方挂进每 Run 的监听器——engine 不触达 observability）。
     * setter 注入可选装配：未装配时退回既有共享监听器形态。
     */
    private LoopEventWiring loopEventWiring;

    @Autowired(required = false)
    void setLoopEventWiring(LoopEventWiring wiring) {
        this.loopEventWiring = wiring;
    }

    /**
     * Run 级监听器：装配工厂在场时按 Run 新建（桥消费方已挂入，Run 结束 close 排空残余）；
     * 缺席时回退既有共享监听器（单元测试形态，不 close）。
     */
    private BoundedEventListener listenerForRun() {
        return loopEventWiring != null ? loopEventWiring.newListener() : eventListener;
    }

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
                            cn.edu.scut.chatbot.tool.assembler.ToolsetAssembler toolsetAssembler,
                            cn.edu.scut.chatbot.context.compaction.TranscriptCompactor transcriptCompactor,
                            cn.edu.scut.chatbot.context.window.WindowAssembler windowAssembler,
                            cn.edu.scut.chatbot.tool.ToolCallGuard toolCallGuard,
                            IdGenerator idGenerator,
                            Clock clock,
                            cn.edu.scut.chatbot.llm.slot.SlotResolver slotResolver,
                            cn.edu.scut.chatbot.llm.slot.CredentialPool credentialPool,
                            cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder) {
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
        this.toolsetAssembler = toolsetAssembler;
        this.transcriptCompactor = transcriptCompactor;
        this.windowAssembler = windowAssembler;
        this.toolCallGuard = toolCallGuard;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.slotResolver = slotResolver;
        this.credentialPool = credentialPool;
        this.costRecorder = costRecorder;
        this.eventListener = new BoundedEventListener();
    }

    /**
     * 槽位装配（Turn/lightTask 形态用 chat_main）：解析失败走既有失败路径
     * （llm.config_missing）——调用方各自 catch 处置，不发明新机制
     */
    private cn.edu.scut.chatbot.loop.ModelConfig chatMainConfig() {
        return slotResolver.resolve(cn.edu.scut.chatbot.llm.slot.ModelCatalog.SLOT_CHAT_MAIN);
    }

    /**
     * 落账 run_kind（turn / lightTask / worker——llm_call_log 按场景钻取口径）
     */
    private static String runKindOf(RunConfig config) {
        if (config.form() == RunConfig.ExecutionForm.LIGHT_TASK) {
            return "lightTask";
        }
        return "turn";
    }

    /**
     * 主模型视觉能力判定（多模态投影规则：vision=false 窗口内一律描述行零 images）
     */
    private boolean visionCapableOf(AgentContext context) {
        return context.modelConfig() != null && context.modelConfig().model() != null
                && context.modelConfig().model().capabilities() != null
                && context.modelConfig().model().capabilities().vision();
    }

    /**
     * resolveCredentials 钩子实现：按槽位绑定的供应商取下一把可用 key——
     * 全部不可用返回 null（钩子既定契约：无凭证按错误结果处理，Run 失败进 Effects 重试）
     */
    private String resolveCredentialFor(AgentContext context) {
        if (context.modelConfig() == null || context.modelConfig().model() == null) {
            return null;
        }
        return credentialPool.nextAvailable(context.modelConfig().model().providerCode());
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
            // Run 前压缩时机：错过 Run 结束时机（压缩失败）的补偿，也是安静档收缩的挂点
            // （缓存过期后的第一次 Run 前收缩——2.3 缓存信号就位前的挂点形态）
            compactBeforeRun(session);
            List<cn.edu.scut.chatbot.loop.message.AgentMessage> history =
                    sessionManager.loadTranscript(session);
            List<AgentTool> turnTools = toolsetAssembler.assemble(RunConfig.ExecutionForm.TURN,
                    turn.getPlatform(), turn.getBotId(), turn.getGroupId());
            cn.edu.scut.chatbot.loop.ModelConfig modelConfig;
            try {
                modelConfig = chatMainConfig();
            } catch (cn.edu.scut.chatbot.llm.slot.ModelCatalog.SlotResolutionException e) {
                log.error("chat_main 槽位解析失败（Turn 生成失败） errorCode=llm.config_missing traceId=n/a：{}",
                        e.getMessage());
                turnCoordinator.generationFailed(turn.getId(), "llm.config_missing:" + e.getMessage());
                return;
            }
            cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome promptOutcome =
                    assembleOutcomeFor(RunConfig.ExecutionForm.TURN, turn.getPlatform(),
                            turn.getBotId(), turn.getGroupId(), null);
            AgentContext context = new AgentContext(promptOutcome.prompt(), history,
                    turnTools.stream().map(AgentTool::descriptor).toList(),
                    modelConfig);
            AgentLoop.RunOutcome outcome = executeSpec(turn.getPlatform(), turn.getBotId(),
                    turn.getGroupId(), turn.getId(), null, context,
                    RunConfig.turnDefaults().withVersionKeys(
                            versionKeysOf(promptOutcome, cn.edu.scut.chatbot.llm.slot.ModelCatalog.SLOT_CHAT_MAIN)),
                    session, turnTools);
            settleTurnOutcome(turn, outcome);
            compactAfterRun(session);
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

            List<AgentTool> taskTools = toolsetAssembler.assemble(RunConfig.ExecutionForm.LIGHT_TASK,
                    task.getPlatform(), task.getBotId(), task.getGroupId());
            cn.edu.scut.chatbot.loop.ModelConfig modelConfig;
            try {
                modelConfig = chatMainConfig();
            } catch (cn.edu.scut.chatbot.llm.slot.ModelCatalog.SlotResolutionException e) {
                log.error("chat_main 槽位解析失败（轻任务失败） errorCode=llm.config_missing traceId=n/a：{}",
                        e.getMessage());
                taskCoordinator.taskConcluded(taskId, null, "llm.config_missing:" + e.getMessage());
                return;
            }
            cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome promptOutcome =
                    assembleOutcomeFor(RunConfig.ExecutionForm.LIGHT_TASK, task.getPlatform(),
                            task.getBotId(), task.getGroupId(), "按任务指令自然地完成定时发言");
            AgentContext context = new AgentContext(
                    promptOutcome.prompt(),
                    history, taskTools.stream().map(AgentTool::descriptor).toList(),
                    modelConfig);
            AgentLoop.RunOutcome outcome = executeSpec(task.getPlatform(), task.getBotId(),
                    task.getGroupId(), null, taskId, context,
                    RunConfig.lightTaskDefaults().withVersionKeys(
                            versionKeysOf(promptOutcome, cn.edu.scut.chatbot.llm.slot.ModelCatalog.SLOT_CHAT_MAIN)),
                    session, taskTools);
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
            compactAfterRun(session);
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

            List<AgentTool> deliveryTools = toolsetAssembler.assemble(RunConfig.ExecutionForm.TURN,
                    task.getPlatform(), task.getBotId(), task.getGroupId());
            cn.edu.scut.chatbot.loop.ModelConfig modelConfig;
            try {
                modelConfig = chatMainConfig();
            } catch (cn.edu.scut.chatbot.llm.slot.ModelCatalog.SlotResolutionException e) {
                log.error("chat_main 槽位解析失败（交付未执行，待 Effects 重试） errorCode=llm.config_missing traceId=n/a：{}",
                        e.getMessage());
                return;
            }
            cn.edu.scut.chatbot.prompt.PromptAssembler.AssembleOutcome promptOutcome =
                    assembleOutcomeFor(RunConfig.ExecutionForm.TURN,
                            task.getPlatform(), task.getBotId(), task.getGroupId(), "任务结论人格化交付");
            AgentContext context = new AgentContext(promptOutcome.prompt(), history,
                    deliveryTools.stream().map(AgentTool::descriptor).toList(),
                    modelConfig);
            executeSpec(task.getPlatform(), task.getBotId(), task.getGroupId(), null, taskId,
                    context, RunConfig.turnDefaults().withVersionKeys(
                            versionKeysOf(promptOutcome, cn.edu.scut.chatbot.llm.slot.ModelCatalog.SLOT_CHAT_MAIN)),
                    session, deliveryTools);
            compactAfterRun(session);
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
     * Run 执行公共体（Turn/轻任务/交付共用组装路径）。投影闭包绑定该群会话的窗口档位——
     * Run 内档位固定（快照纪律），新 Run 读新档。
     */
    private AgentLoop.RunOutcome executeSpec(String platform, String botId, String groupId,
                                             Long turnId, Long taskId, AgentContext context,
                                             RunConfig config, AgentSessionDO session,
                                             List<AgentTool> tools) {
        String windowLevel = session.getWindowLevel() != null
                ? session.getWindowLevel() : AgentSessionDO.WINDOW_ACTIVE;
        long runId = idGenerator.nextId();
        AgentLoop.RunSpec spec = new AgentLoop.RunSpec(
                runId, platform, botId, groupId, turnId, taskId, context, config,
                LoopHooks.builder(working -> contextAwareProjector.convert(working, windowLevel,
                                visionCapableOf(context)))
                        .transformContext(original -> injectCurrentNotes(original, session))
                        .resolveCredentials(() -> resolveCredentialFor(context))
                        .beforeToolCall(toolCallGuard.forRun(platform, botId, groupId))
                        .build(),
                toolsByName(tools),
                // 落账装饰器：Run 级 meta（run_kind 按执行形态）随 Run 绑定（research R8）；
                // replay 标记贯通（T055）：dry-run 重放的 Run 落账 is_replay=1
                new cn.edu.scut.chatbot.engine.CostAwareStreamFn(streamFn, costRecorder,
                        new cn.edu.scut.chatbot.llm.gateway.LlmCallMeta(platform, botId, groupId,
                                runId, runKindOf(config), "run-" + runId, config.replay())),
                clock, idGenerator::nextId,
                (completedRunId, turnMessages) -> {
                    if (config.form() == RunConfig.ExecutionForm.LIGHT_TASK) {
                        // 轻任务腹稿只写任务执行记录，不进群聊历史（设计 :505；
                        // 发言经 sendMessage 出口已在 outbox 留痕）
                        taskCoordinator.taskProgress(taskId,
                                "轮次完成：" + turnMessages.size() + " 条消息（不进群聊历史）");
                        return;
                    }
                    sessionManager.appendTranscript(session, turnMessages, completedRunId);
                },
                listenerForRun());
        try {
            return agentLoop.execute(spec);
        } finally {
            if (loopEventWiring != null) {
                eventListenerOfSpec(spec).close();
            }
        }
    }

    /**
     * 从 RunSpec 取回监听器（close 排空残余事件用）
     */
    private static BoundedEventListener eventListenerOfSpec(AgentLoop.RunSpec spec) {
        return (BoundedEventListener) spec.eventListener();
    }

    /**
     * Run 前压缩时机：同步执行（错过 Run 结束时机的补偿与安静档收缩挂点——
     * 本次 Run 的窗口直接取收缩后的转录）
     */
    private void compactBeforeRun(AgentSessionDO session) {
        compactOnce(session);
    }

    /**
     * 预防性压缩通道（Run 结束查水位——设计压缩触发双通道之一；应急通道由
     * context_overflow 错误驱动重试，2.3 模型侧错误码就位后挂接）。
     * 异步执行：不占推理权时间（设计 :1296"Run 结束、轮次落库后异步执行"）
     */
    private void compactAfterRun(AgentSessionDO session) {
        Thread.ofVirtual().name("transcript-compact").start(() -> compactOnce(session));
    }

    /**
     * 水位检查与压缩执行（失败只告警——压缩是增强动作，不影响生成结果）
     */
    private void compactOnce(AgentSessionDO session) {
        try {
            String level = session.getWindowLevel() != null
                    ? session.getWindowLevel() : AgentSessionDO.WINDOW_ACTIVE;
            List<cn.edu.scut.chatbot.loop.message.AgentMessage> transcript =
                    sessionManager.loadTranscript(session);
            int estimated = transcript.stream()
                    .mapToInt(GenerationRunner::projectionTextOf)
                    .sum();
            int outputReserve = 4096;
            int triggerLine = windowAssembler.compactTriggerLine(level, outputReserve);
            if (estimated > triggerLine) {
                transcriptCompactor.compactIfNeeded(session, estimated, triggerLine);
            }
        } catch (Exception e) {
            // 压缩失败不影响刚完成的生成（下个 Run 前后时机再试）
            log.warn("压缩失败（不影响生成结果） sessionId={}", session.getId(), e);
        }
    }

    /**
     * 投影文本估算口径（与窗口组装器的估算依据一致——摘要取正文、事件取一行描述、
     * 基准条目取主文本，不用 record 全字段 toString 防系统性高估）
     */
    private static int projectionTextOf(cn.edu.scut.chatbot.loop.message.AgentMessage message) {
        String text;
        if (message instanceof cn.edu.scut.chatbot.loop.message.SummaryEntry summary) {
            text = summary.summaryText();
        } else if (message instanceof cn.edu.scut.chatbot.loop.message.EventEntry event) {
            text = event.summaryLine();
        } else if (message instanceof cn.edu.scut.chatbot.loop.message.AssistantMessage assistant) {
            text = assistant.textContent();
        } else if (message instanceof cn.edu.scut.chatbot.loop.message.ToolResultMessage toolResult) {
            StringBuilder builder = new StringBuilder();
            for (var content : toolResult.content()) {
                if (content instanceof cn.edu.scut.chatbot.loop.message.TextContent textContent) {
                    builder.append(textContent.text());
                }
            }
            text = builder.toString();
        } else if (message instanceof cn.edu.scut.chatbot.loop.message.UserMessage user) {
            StringBuilder builder = new StringBuilder();
            for (var part : user.parts()) {
                if (part instanceof cn.edu.scut.chatbot.contract.message.TextPart textPart) {
                    builder.append(textPart.text());
                }
            }
            text = builder.toString();
        } else if (message instanceof cn.edu.scut.chatbot.loop.message.LinkCardEntry card) {
            text = card.summary() != null ? card.summary() : card.title();
        } else {
            text = "";
        }
        return cn.edu.scut.chatbot.context.window.TokenEstimator.estimate(
                text == null ? "" : text);
    }

    /**
     * transformContext 现注区注入（任务清单当前态 + 态度行——逐人态度是
     * 「这次跟谁说话」的函数每次都在变，落现注区尾部零缓存代价）
     */
    private AgentContext injectCurrentNotes(AgentContext original, AgentSessionDO session) {
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> augmented =
                new ArrayList<>(original.messages());
        augmented.add(new SystemNote(
                Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 50, null,
                "runtime-note", "[系统现注] 当前时间：" + clock.instant()
                + "；进行中任务：" + runningTaskSummary(session)));
        appendAttitudeNote(augmented, original, session);
        appendEmotionNote(augmented, session);
        return new AgentContext(original.systemPrompt(), augmented,
                original.tools(), original.modelConfig());
    }

    /**
     * 情绪行现注（T047）：现注区尾部一行——「不播报」防每句话都喊"人家好开心"；
     * 情绪不进 systemPrompt（每次可变进系统区即打断前缀缓存），每次生成前现注
     */
    private void appendEmotionNote(List<cn.edu.scut.chatbot.loop.message.AgentMessage> augmented,
                                   AgentSessionDO session) {
        if (emotionService == null) {
            return;
        }
        try {
            cn.edu.scut.chatbot.memory.emotion.EmotionState emotion = emotionService.read(
                    new cn.edu.scut.chatbot.bootstrap.GroupKey(
                            session.getPlatform(), session.getBotId(), session.getGroupId()));
            String line = "[当前心情：" + emotion.label()
                    + (emotion.cause() == null || emotion.cause().isBlank()
                    ? "" : "，起因：" + emotion.cause())
                    + "]，自然体现在语气与用词中，不要直接播报";
            augmented.add(new SystemNote(
                    Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 50, null,
                    "emotion-note", line));
        } catch (Exception e) {
            // 情绪行是增强注记——失败不影响生成
            log.warn("情绪行现注失败（不影响生成） sessionId={}", session.getId(), e);
        }
    }

    /**
     * 态度行现注（T031）：本批触发器与锚定区涉及的发言人中态度显著者各一行，
     * 整块以数据身份定界（群友相关数据归不可信定界注入）；无显著者零注入
     */
    private void appendAttitudeNote(List<cn.edu.scut.chatbot.loop.message.AgentMessage> augmented,
                                    AgentContext original, AgentSessionDO session) {
        if (attitudeNoteRenderer == null) {
            return;   // 未装配（隔离测试上下文）零注入——引擎主路径不受影响
        }
        try {
            List<String> lines = attitudeNoteRenderer.render(
                    new cn.edu.scut.chatbot.bootstrap.GroupKey(
                            session.getPlatform(), session.getBotId(), session.getGroupId()),
                    recentSenderIds(original));
            if (lines.isEmpty()) {
                return;
            }
            augmented.add(new SystemNote(
                    Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 50, null,
                    "attitude-note",
                    delimitGuard != null
                            ? delimitGuard.wrap("本批发言人的态度参考（数据）：\n" + String.join("\n", lines), DelimitHeaders.Src.VARIABLE)
                            : DelimitHeaders.wrap("本批发言人的态度参考（数据）：\n" + String.join("\n", lines), DelimitHeaders.Src.VARIABLE)));
        } catch (Exception e) {
            // 态度行是增强注记——失败不影响生成
            log.warn("态度行现注失败（不影响生成） sessionId={}", session.getId(), e);
        }
    }

    /**
     * 近期窗口的发言人（去重、最近优先——态度行只关心「这次跟谁说话」）
     */
    private List<String> recentSenderIds(AgentContext original) {
        java.util.LinkedHashSet<String> senders = new java.util.LinkedHashSet<>();
        List<cn.edu.scut.chatbot.loop.message.AgentMessage> messages = original.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof cn.edu.scut.chatbot.loop.message.UserMessage user) {
                if (user.senderUserId() != null) {
                    senders.add(user.senderUserId());
                }
            }
        }
        return new ArrayList<>(senders);
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
     * 出站快筛（US8 硬防线兜底校验：裸文本补投同样必须过词库检查，绝不绕过快筛写 Outbox）
     */
    private cn.edu.scut.chatbot.security.gatepolicy.OutboundScreenService outboundScreenService;

    @Autowired(required = false)
    void setOutboundScreenService(cn.edu.scut.chatbot.security.gatepolicy.OutboundScreenService service) {
        this.outboundScreenService = service;
    }

    /**
     * 兜底发言补投：经 sendMessage 唯一出口落 outbox（裸文本只是腹稿——投递必经出口且过出站快筛）
     */
    private long sendFallback(TurnDO turn, String fallbackText) {
        try {
            if (outboundScreenService != null) {
                var verdict = outboundScreenService.screen(fallbackText, null,
                        cn.edu.scut.chatbot.security.audit.SecurityDims.ofGroup(
                                turn.getPlatform(), turn.getBotId(), turn.getGroupId()),
                        org.slf4j.MDC.get("traceId"));
                if (verdict.blocked()) {
                    log.warn("兜底发言被出站快筛拦截（Outbox 零写入） turnId={} errorCode={}",
                            turn.getId(), verdict.errorCode());
                    return -1;
                }
            }
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

    private Map<String, AgentTool> toolsByName(List<AgentTool> tools) {
        Map<String, AgentTool> byName = new HashMap<>();
        for (AgentTool tool : tools) {
            byName.put(tool.descriptor().name(), tool);
        }
        return byName;
    }
}
