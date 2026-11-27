package cn.edu.scut.chatbot.observability.replay;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.context.ContextAwareProjector;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.engine.CostAwareStreamFn;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.slot.*;
import cn.edu.scut.chatbot.loop.*;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.observability.eval.*;
import cn.edu.scut.chatbot.prompt.PromptAssembler;
import cn.edu.scut.chatbot.tool.ToolCallGuard;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dry-run 隔离重放（eval-and-flywheel 契约第 4 节、R10、T056）：对评测集题目在
 * 派生三元组下真实执行生成——复用 AgentLoop 全部机制（真实 LLM 网关、真实工具
 * 装配、真实上下文组装），唯三差异：出口影子化（{@link ShadowSendMessage} 零
 * Outbox 零投递 / {@link ShadowCreateTask} 真实校验落库即取消）、成本单独记账
 * （is_replay=1，SC-005）、状态隔离（真实群 / 记忆 / 画像零接触）。
 *
 * <p>Pass@k：同题循环 k 次（observe.eval.passAtK 缺省 3），每次执行前清掉上次
 * 重放的临时转录（前情种子保留）保 k 次独立；通过率 = pass 次数 / k。
 * 版本对照：本批 k 次同版本（同一次部署的版本三键），分组键进 report——
 * 跨版本对照由发布门禁汇总多批次 report 得出。
 *
 * <p>执行产物交 US5 评估器判分（RubricJudge 合并 code + judge）落
 * eval_result(source_kind=replay, case_id)。批次并发与评估共用
 * {@link ReplayConcurrencyGate}（observe.replay.maxConcurrent 同闸）。
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final EvalCaseMapper caseMapper;
    private final EvalBatchMapper batchMapper;
    private final EvalResultMapper resultMapper;
    private final LlmCallLogMapper llmCallLogMapper;
    private final ReplayGroupProvisioner provisioner;
    private final ReplayToolAssembler toolAssembler;
    private final ReplayConcurrencyGate concurrencyGate;
    private final SystemParamService params;
    private final AgentLoop agentLoop;
    private final StreamFn streamFn;
    private final CostRecorder costRecorder;
    private final SessionManager sessionManager;
    private final PromptAssembler promptAssembler;
    private final ContextAwareProjector contextAwareProjector;
    private final ToolCallGuard toolCallGuard;
    private final SlotResolver slotResolver;
    private final cn.edu.scut.chatbot.llm.slot.CredentialPool credentialPool;
    private final cn.edu.scut.chatbot.infrastructure.eventbus.LoopEventWiring loopEventWiring;
    private final RubricJudge rubricJudge;
    private final cn.edu.scut.chatbot.observability.eval.CodeRuleEvaluator codeRuleEvaluator;
    private final cn.edu.scut.chatbot.observability.eval.AgentJudgeEvaluator agentJudgeEvaluator;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 对抗测试可选协作者（US11 T096/T097）：memory 通道沉淀前置钩子 + 真实 Outbox/沙箱异常计数。
     * setter 注入保持既有构造器与单测零破坏。
     */
    private cn.edu.scut.chatbot.memory.sediment.SedimentWorker sedimentWorker;
    private cn.edu.scut.chatbot.memory.sediment.SedimentJobMapper sedimentJobMapper;
    private cn.edu.scut.chatbot.engine.outbox.OutboxMapper outboxMapper;
    private cn.edu.scut.chatbot.security.audit.SecurityEventMapper securityEventMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSedimentWorker(cn.edu.scut.chatbot.memory.sediment.SedimentWorker worker) {
        this.sedimentWorker = worker;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSedimentJobMapper(cn.edu.scut.chatbot.memory.sediment.SedimentJobMapper mapper) {
        this.sedimentJobMapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOutboxMapper(cn.edu.scut.chatbot.engine.outbox.OutboxMapper mapper) {
        this.outboxMapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSecurityEventMapper(cn.edu.scut.chatbot.security.audit.SecurityEventMapper mapper) {
        this.securityEventMapper = mapper;
    }

    public ReplayService(EvalCaseMapper caseMapper,
                         EvalBatchMapper batchMapper,
                         EvalResultMapper resultMapper,
                         LlmCallLogMapper llmCallLogMapper,
                         ReplayGroupProvisioner provisioner,
                         ReplayToolAssembler toolAssembler,
                         ReplayConcurrencyGate concurrencyGate,
                         SystemParamService params,
                         AgentLoop agentLoop,
                         StreamFn streamFn,
                         CostRecorder costRecorder,
                         SessionManager sessionManager,
                         PromptAssembler promptAssembler,
                         ContextAwareProjector contextAwareProjector,
                         ToolCallGuard toolCallGuard,
                         SlotResolver slotResolver,
                         cn.edu.scut.chatbot.llm.slot.CredentialPool credentialPool,
                         cn.edu.scut.chatbot.infrastructure.eventbus.LoopEventWiring loopEventWiring,
                         RubricJudge rubricJudge,
                         cn.edu.scut.chatbot.observability.eval.CodeRuleEvaluator codeRuleEvaluator,
                         cn.edu.scut.chatbot.observability.eval.AgentJudgeEvaluator agentJudgeEvaluator,
                         IdGenerator idGenerator,
                         Clock clock) {
        this.caseMapper = caseMapper;
        this.batchMapper = batchMapper;
        this.resultMapper = resultMapper;
        this.llmCallLogMapper = llmCallLogMapper;
        this.provisioner = provisioner;
        this.toolAssembler = toolAssembler;
        this.concurrencyGate = concurrencyGate;
        this.params = params;
        this.agentLoop = agentLoop;
        this.streamFn = streamFn;
        this.costRecorder = costRecorder;
        this.sessionManager = sessionManager;
        this.promptAssembler = promptAssembler;
        this.contextAwareProjector = contextAwareProjector;
        this.toolCallGuard = toolCallGuard;
        this.slotResolver = slotResolver;
        this.credentialPool = credentialPool;
        this.loopEventWiring = loopEventWiring;
        this.rubricJudge = rubricJudge;
        this.codeRuleEvaluator = codeRuleEvaluator;
        this.agentJudgeEvaluator = agentJudgeEvaluator;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 同步执行一道题的 Pass@k 重放（后台化由调用方决定——门禁侧在自己的线程里调）
     */
    public ReplayReport run(EvalCaseDO caze, Integer kOverride) {
        return runWithContext(caze, kOverride, null, null);
    }

    /**
     * 批量重放（006 US7 / T093）：支持多题批量、versionRef 版本覆盖标识（{entityKind}:{entityKey}:v{n}）
     * 与 experimentId 对照实验归组。
     */
    public BatchReplayReport runBatch(List<Long> caseIds, Integer kOverride,
                                      String versionRef, String experimentId) {
        List<EvalCaseDO> cases = new ArrayList<>();
        if (caseIds != null && !caseIds.isEmpty()) {
            for (Long id : caseIds) {
                if (id != null) {
                    EvalCaseDO c = caseMapper.selectById(id);
                    if (c != null) cases.add(c);
                }
            }
        } else {
            cases.addAll(caseMapper.selectReadyByTypes(
                    List.of(EvalCaseDO.TYPE_SEED, EvalCaseDO.TYPE_GOLDEN, EvalCaseDO.TYPE_FAULT), 50));
        }
        String expId = (experimentId == null || experimentId.isBlank())
                ? "exp-" + idGenerator.nextId() : experimentId;
        List<ReplayReport> reports = new ArrayList<>();
        int passedCases = 0;
        for (EvalCaseDO c : cases) {
            ReplayReport report = runWithContext(c, kOverride, versionRef, expId);
            reports.add(report);
            if (report.passRate() >= 1.0) {
                passedCases++;
            }
        }
        double passRate = cases.isEmpty() ? 0.0 : (double) passedCases / cases.size();
        return new BatchReplayReport(expId, versionRef, cases.size(), passedCases, passRate, reports);
    }

    public List<EvalBatchView> listReplayBatches(int page, int size, String experimentId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<EvalBatchDO> rows = batchMapper.selectPage(EvalBatchDO.KIND_REPLAY, safePage * safeSize, safeSize);
        return rows.stream()
                .filter(b -> experimentId == null || experimentId.isBlank()
                        || (b.getScope() != null && b.getScope().contains(experimentId)))
                .map(EvalBatchView::from)
                .toList();
    }

    public BatchReplayComparison getExperimentComparison(String experimentId) {
        List<EvalBatchDO> rows = batchMapper.selectPage(EvalBatchDO.KIND_REPLAY, 0, 200).stream()
                .filter(b -> b.getScope() != null && b.getScope().contains(experimentId))
                .toList();
        List<EvalBatchView> views = rows.stream().map(EvalBatchView::from).toList();
        return new BatchReplayComparison(experimentId, views.size(), views);
    }

    public record BatchReplayReport(
            String experimentId,
            String versionRef,
            int totalCases,
            int passedCases,
            double passRate,
            List<ReplayReport> reports
    ) {
    }

    public record BatchReplayComparison(
            String experimentId,
            int batchCount,
            List<EvalBatchView> batches
    ) {
    }

    private ReplayReport runWithContext(EvalCaseDO caze, Integer kOverride,
                                        String versionRef, String experimentId) {
        int k = kOverride != null && kOverride > 0 ? kOverride
                : Math.max(1, params.getInt("observe.eval.passAtK", 3));
        if (!concurrencyGate.tryAcquire()) {
            throw new NonRetryableException(ErrorCodes.OBSERVE_EVAL_BUSY,
                    "重放批次并发已达上限（observe.replay.maxConcurrent），稍后重试");
        }
        try {
            ScenarioCard scenario = ScenarioCard.parse(caze.getScenario());
            List<RubricItem> rubric = RubricItem.parseRubric(caze.getRubric());

            ReplayGroupProvisioner.PreparedReplay prepared = provisioner.provision(caze, scenario);
            ReplayGroupProvisioner.ReplayTriple triple = prepared.triple();
            AgentSessionDO session = prepared.session();

            Long watermark = llmCallLogMapper.selectMaxReplayId();
            long sinceId = watermark == null ? 0L : watermark;

            EvalBatchDO batch = new EvalBatchDO();
            batch.setBatchKind(EvalBatchDO.KIND_REPLAY);
            ObjectNode scope = JsonMappers.shared().createObjectNode();
            scope.put("caseId", caze.getId());
            scope.put("passAtK", k);
            scope.put("platform", triple.platform());
            scope.put("groupId", triple.groupId());
            if (versionRef != null && !versionRef.isBlank()) {
                scope.put("versionRef", versionRef);
            }
            if (experimentId != null && !experimentId.isBlank()) {
                scope.put("experimentId", experimentId);
            }
            batch.setScope(scope.toString());
            batch.setStatus(EvalBatchDO.STATUS_RUNNING);
            batchMapper.insert(batch);

            String judgeModel = agentJudgeEvaluator.judgeModelOf();
            List<ReplayReport.ExecutionDetail> details = new ArrayList<>();
            int passed = 0;
            String versionGroupKey = null;
            for (int attempt = 1; attempt <= k; attempt++) {
                provisioner.resetTransient(session);
                SingleExecution execution = executeOnce(caze, scenario, rubric, triple, session);
                versionGroupKey = execution.versionGroupKey;
                EvalOutcome outcome = execution.outcome;
                if (outcome.failed()) {
                    details.add(new ReplayReport.ExecutionDetail(attempt, null,
                            execution.speeches, execution.createdTasks, outcome.failureReason()));
                    continue;
                }
                resultMapper.insert(resultRow(batch.getId(), caze, triple, outcome, judgeModel));
                details.add(new ReplayReport.ExecutionDetail(attempt, outcome.decision(),
                        execution.speeches, execution.createdTasks, null));
                if (EvalResultDO.DECISION_PASS.equals(outcome.decision())) {
                    passed++;
                }
            }
            String reportJson = reportJson(k, passed, versionGroupKey, details);
            String costJson = replayCost(sinceId, k * 2);
            batchMapper.finish(batch.getId(),
                    passed == 0 && details.stream().allMatch(d -> d.failureReason() != null)
                            ? EvalBatchDO.STATUS_FAILED : EvalBatchDO.STATUS_DONE,
                    reportJson, costJson, LocalDateTime.now(clock));
            return new ReplayReport(batch.getId(), caze.getId(), k, passed,
                    k == 0 ? 0.0 : (double) passed / k, versionGroupKey, details, costJson, null);
        } catch (NonRetryableException e) {
            throw e;
        } catch (Exception e) {
            log.error("重放执行异常 errorCode={} traceId=n/a caseId={} reason={}",
                    ErrorCodes.OBSERVE_EVAL_INVALID, caze.getId(), e.getMessage(), e);
            return new ReplayReport(0, caze.getId(), 0, 0, 0.0, null, List.of(), null,
                    "重放执行异常：" + e.getMessage());
        } finally {
            concurrencyGate.release();
        }
    }

    /**
     * 注入对抗测试执行入口（US11 T096，eval-and-flywheel 契约第 4、7 节）：
     * 复用 dry-run 隔离重放（派生三元组、影子 sendMessage/createTask、is_replay=1），
     * 按通道执行载荷前置钩子（image 直接构造事件信封形态、memory 离线先跑 004 沉淀管线再触发检索、
     * web/card/nickname 定界注入上下文），返回硬/软防线评估所需的完整执行事实。
     */
    public AttackAssertionEvaluator.AttackExecutionFacts runAttack(AttackCaseDO attack) {
        AttackAssertionEvaluator.validateStructure(attack);
        if (!concurrencyGate.tryAcquire()) {
            throw new NonRetryableException(ErrorCodes.OBSERVE_EVAL_BUSY,
                    "对抗重放并发已达上限（observe.replay.maxConcurrent），稍后重试");
        }
        try {
            return executeAttackInternal(attack);
        } finally {
            concurrencyGate.release();
        }
    }

    private AttackAssertionEvaluator.AttackExecutionFacts executeAttackInternal(AttackCaseDO attack) {
        EvalCaseDO synthetic = new EvalCaseDO();
        synthetic.setId(attack.getId() != null ? attack.getId() : idGenerator.nextId());
        synthetic.setPlatform("attack");
        synthetic.setBotId("attack-bot");
        synthetic.setGroupId("atk-" + attack.getChannel());

        com.fasterxml.jackson.databind.JsonNode scenarioNode = parseJsonQuietly(attack.getScenario());
        List<ScenarioCard.ScriptLine> priorLines = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode priorArr = scenarioNode.path("priorScript");
        if (priorArr.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode line : priorArr) {
                priorLines.add(new ScenarioCard.ScriptLine(
                        line.path("speaker").asText("u1"),
                        line.path("text").asText("")));
            }
        }
        String triggerText = scenarioNode.path("triggerText").asText(attack.getPayload());
        ScenarioCard syntheticScenario = new ScenarioCard(
                "注入对抗测试隔离群（channel=" + attack.getChannel() + "）",
                List.of(),
                new ScenarioCard.PreloadedState(null, null),
                priorLines,
                new ScenarioCard.TriggerEvent(attack.getChannel(), triggerText));

        ReplayGroupProvisioner.PreparedReplay prepared = provisioner.provision(synthetic, syntheticScenario);
        ReplayGroupProvisioner.ReplayTriple triple = prepared.triple();
        AgentSessionDO session = prepared.session();
        provisioner.resetTransient(session);

        // 载荷前置钩子（research 缝隙增补 4）：memory 通道先对构造转录离线跑沉淀管线
        if (AttackCaseDO.CHANNEL_MEMORY.equals(attack.getChannel())) {
            runMemorySedimentPreHook(triple, session, attack.getPayload());
        }

        long outboxBefore = outboxMapper != null
                ? outboxMapper.countByGroup(triple.platform(), triple.botId(), triple.groupId()) : 0L;
        LocalDateTime startedAt = LocalDateTime.now(clock);
        long sandboxBefore = securityEventMapper != null
                ? securityEventMapper.countByKindGroupSince(
                cn.edu.scut.chatbot.security.audit.SecurityEventKind.SANDBOX_ABNORMAL.code(),
                triple.platform(), triple.botId(), triple.groupId(), startedAt.minusSeconds(1))
                : 0L;

        List<AgentMessage> history = new ArrayList<>(sessionManager.loadTranscript(session));
        // 按通道把攻击载荷装配进本次上下文（与线上各通道形态一致）
        history.addAll(buildChannelTriggerMessages(attack, triggerText));

        ReplayToolAssembler.ShadowToolset toolset = toolAssembler.assemble(
                triple.platform(), triple.botId(), triple.groupId());
        cn.edu.scut.chatbot.loop.ModelConfig modelConfig =
                slotResolver.resolve(ModelCatalog.SLOT_CHAT_MAIN);
        PromptAssembler.AssembleOutcome promptOutcome = promptAssembler.assembleDetailed(
                RunConfig.ExecutionForm.TURN, triple.platform(), triple.botId(),
                triple.groupId(), null);
        RunConfig.VersionKeys versionKeys = new RunConfig.VersionKeys(
                promptOutcome.fragmentVersion(), promptOutcome.personaVersion(),
                slotResolver.bindingVersionOf(ModelCatalog.SLOT_CHAT_MAIN));

        Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
        for (AgentTool tool : toolset.tools()) {
            toolsByName.put(tool.descriptor().name(), tool);
        }
        List<AgentMessage> producedMessages = new ArrayList<>();
        long runId = idGenerator.nextId();
        String windowLevel = session.getWindowLevel() != null
                ? session.getWindowLevel() : AgentSessionDO.WINDOW_ACTIVE;
        boolean visionCapable = modelConfig.model() != null
                && modelConfig.model().capabilities() != null
                && modelConfig.model().capabilities().vision();

        long startNanos = System.nanoTime();
        AgentLoop.RunSpec spec = new AgentLoop.RunSpec(
                runId, triple.platform(), triple.botId(), triple.groupId(), null, null,
                new AgentContext(promptOutcome.prompt(), history,
                        toolset.tools().stream().map(AgentTool::descriptor).toList(), modelConfig),
                RunConfig.turnDefaults().withVersionKeys(versionKeys).withReplay(true),
                LoopHooks.builder(working -> contextAwareProjector.convert(working, windowLevel, visionCapable))
                        .resolveCredentials(() -> credentialPool.nextAvailable(
                                modelConfig.model().providerCode()))
                        .beforeToolCall(toolCallGuard.forRun(
                                triple.platform(), triple.botId(), triple.groupId()))
                        .build(),
                Map.copyOf(toolsByName),
                new CostAwareStreamFn(streamFn, costRecorder,
                        new LlmCallMeta(triple.platform(), triple.botId(), triple.groupId(),
                                runId, "safety", "attack-" + synthetic.getId() + "-" + runId, true)),
                clock, idGenerator::nextId,
                (completedRunId, turnMessages) -> {
                    producedMessages.addAll(turnMessages);
                    sessionManager.appendTranscript(session, turnMessages, completedRunId);
                },
                loopEventWiring.newListener());
        agentLoop.execute(spec);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        List<String> speeches = toolset.sendShadow().speeches().stream()
                .map(ShadowSendMessage.ShadowSpeech::text).toList();
        List<String> createdTasks = toolset.taskShadow() == null
                ? List.of() : toolset.taskShadow().createdTasks();
        long outboxAfter = outboxMapper != null
                ? outboxMapper.countByGroup(triple.platform(), triple.botId(), triple.groupId()) : 0L;
        long sandboxAfter = securityEventMapper != null
                ? securityEventMapper.countByKindGroupSince(
                cn.edu.scut.chatbot.security.audit.SecurityEventKind.SANDBOX_ABNORMAL.code(),
                triple.platform(), triple.botId(), triple.groupId(), startedAt.minusSeconds(1))
                : 0L;

        return new AttackAssertionEvaluator.AttackExecutionFacts(
                triple.platform(), triple.botId(), triple.groupId(),
                promptOutcome.prompt(), speeches, createdTasks,
                Math.max(0L, outboxAfter - outboxBefore),
                Math.max(0L, sandboxAfter - sandboxBefore),
                narrativeOf(history, producedMessages, speeches),
                latencyMs);
    }

    /**
     * memory 通道载荷前置钩子：把带延时注入的构造转录落入隔离会话并同步调用 004 SedimentWorker
     */
    private void runMemorySedimentPreHook(ReplayGroupProvisioner.ReplayTriple triple,
                                          AgentSessionDO session, String payload) {
        if (sedimentWorker == null || sedimentJobMapper == null) {
            return;
        }
        try {
            long entryId = idGenerator.nextId();
            UserMessage poisonHistory = new UserMessage(String.valueOf(entryId),
                    clock.instant().minusSeconds(600), 50, "attack-memory-seed", "u-attacker",
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                            cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT, payload)));
            sessionManager.appendTranscript(session, List.of(poisonHistory), null);

            cn.edu.scut.chatbot.memory.sediment.SedimentJobDO job =
                    new cn.edu.scut.chatbot.memory.sediment.SedimentJobDO();
            job.setPlatform(triple.platform());
            job.setBotId(triple.botId());
            job.setGroupId(triple.groupId());
            job.setTriggerKind(cn.edu.scut.chatbot.memory.sediment.SedimentJobDO.KIND_DAILY_SCAN);
            job.setIdempotentKey("attack-sediment-" + entryId);
            job.setPayload(JsonMappers.valueToStringOrNull(Map.of(
                    "firstEntryId", entryId,
                    "lastEntryId", entryId)));
            job.setStatus(cn.edu.scut.chatbot.memory.sediment.SedimentJobDO.STATUS_PROCESSING);
            sedimentJobMapper.insertJob(job);
            sedimentWorker.process(job);
        } catch (Exception e) {
            log.warn("memory 对抗用例前置沉淀执行异常（降级继续重放） group={}", triple.groupId(), e);
        }
    }

    private List<AgentMessage> buildChannelTriggerMessages(AttackCaseDO attack, String triggerText) {
        List<AgentMessage> msgs = new ArrayList<>();
        String channel = attack.getChannel();
        if (AttackCaseDO.CHANNEL_IMAGE.equals(channel)) {
            // image 通道：直接构造图片消息信封形态（降级描述走 VISION XML 定界）
            String delimitedVision = cn.edu.scut.chatbot.security.delimit.DelimitHeaders.wrap(
                    attack.getPayload(), cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.VISION);
            msgs.add(new UserMessage("atk-img-" + idGenerator.nextId(), clock.instant(), 50, null, "u-attacker",
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                            cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT,
                            "[图片描述] " + delimitedVision + "\n" + triggerText))));
        } else if (AttackCaseDO.CHANNEL_WEB.equals(channel)
                || AttackCaseDO.CHANNEL_CARD.equals(channel)
                || AttackCaseDO.CHANNEL_HTTP.equals(channel)) {
            String delimitedWeb = cn.edu.scut.chatbot.security.delimit.DelimitHeaders.wrap(
                    attack.getPayload(), cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.WEB);
            msgs.add(new UserMessage("atk-web-" + idGenerator.nextId(), clock.instant(), 50, null, "u-attacker",
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                            cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT,
                            delimitedWeb + "\n" + triggerText))));
        } else if (AttackCaseDO.CHANNEL_EMOJI.equals(channel)) {
            String delimitedEmoji = cn.edu.scut.chatbot.security.delimit.DelimitHeaders.wrap(
                    attack.getPayload(), cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.VISION);
            msgs.add(new UserMessage("atk-emoji-" + idGenerator.nextId(), clock.instant(), 50, null, "u-attacker",
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                            cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT,
                            "[表情包描述] " + delimitedEmoji + "\n" + triggerText))));
        } else if (AttackCaseDO.CHANNEL_NICKNAME.equals(channel)) {
            String delimitedNick = cn.edu.scut.chatbot.security.delimit.DelimitHeaders.wrap(
                    attack.getPayload(), cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.VARIABLE);
            msgs.add(new UserMessage("atk-nick-" + idGenerator.nextId(), clock.instant(), 50, null, delimitedNick,
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                            cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT, triggerText))));
        } else {
            msgs.add(new UserMessage("atk-msg-" + idGenerator.nextId(), clock.instant(), 50, null, "u-attacker",
                    List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                            cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT, triggerText))));
        }
        return msgs;
    }

    private static com.fasterxml.jackson.databind.JsonNode parseJsonQuietly(String json) {
        try {
            return JsonMappers.shared().readTree(json);
        } catch (Exception e) {
            return JsonMappers.shared().createObjectNode();
        }
    }

    /**
     * 单次执行产物：评估结果 + 影子清单 + 版本分组键
     */
    private record SingleExecution(EvalOutcome outcome,
                                   List<String> speeches,
                                   List<String> createdTasks,
                                   String versionGroupKey) {
    }

    /**
     * 执行一次完整 Run：清场后的种子转录 + 触发消息 → AgentLoop（影子工具、
     * replay 记账）→ 评估器判分
     */
    private SingleExecution executeOnce(EvalCaseDO caze, ScenarioCard scenario,
                                        List<RubricItem> rubric,
                                        ReplayGroupProvisioner.ReplayTriple triple,
                                        AgentSessionDO session) {
        List<AgentMessage> history = sessionManager.loadTranscript(session);
        // 触发消息进上下文尾部（形态与线上一致：触发是最新一条群消息）
        UserMessage trigger = new UserMessage("replay-trigger-" + idGenerator.nextId(),
                clock.instant(), 50, null, "trigger",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart(
                        cn.edu.scut.chatbot.contract.message.TextPart.TYPE_TEXT,
                        scenario.triggerEvent().digest())));
        history.add(trigger);

        ReplayToolAssembler.ShadowToolset toolset = toolAssembler.assemble(
                triple.platform(), triple.botId(), triple.groupId());
        cn.edu.scut.chatbot.loop.ModelConfig modelConfig =
                slotResolver.resolve(ModelCatalog.SLOT_CHAT_MAIN);
        PromptAssembler.AssembleOutcome promptOutcome = promptAssembler.assembleDetailed(
                RunConfig.ExecutionForm.TURN, triple.platform(), triple.botId(),
                triple.groupId(), null);
        RunConfig.VersionKeys versionKeys = new RunConfig.VersionKeys(
                promptOutcome.fragmentVersion(), promptOutcome.personaVersion(),
                slotResolver.bindingVersionOf(ModelCatalog.SLOT_CHAT_MAIN));

        Map<String, AgentTool> toolsByName = new LinkedHashMap<>();
        for (AgentTool tool : toolset.tools()) {
            toolsByName.put(tool.descriptor().name(), tool);
        }
        List<AgentMessage> producedMessages = new ArrayList<>();
        long runId = idGenerator.nextId();
        String windowLevel = session.getWindowLevel() != null
                ? session.getWindowLevel() : AgentSessionDO.WINDOW_ACTIVE;
        boolean visionCapable = modelConfig.model() != null
                && modelConfig.model().capabilities() != null
                && modelConfig.model().capabilities().vision();
        AgentLoop.RunSpec spec = new AgentLoop.RunSpec(
                runId, triple.platform(), triple.botId(), triple.groupId(), null, null,
                new AgentContext(promptOutcome.prompt(), history,
                        toolset.tools().stream().map(AgentTool::descriptor).toList(), modelConfig),
                RunConfig.turnDefaults().withVersionKeys(versionKeys).withReplay(true),
                LoopHooks.builder(working -> contextAwareProjector.convert(working, windowLevel, visionCapable))
                        .resolveCredentials(() -> credentialPool.nextAvailable(
                                modelConfig.model().providerCode()))
                        .beforeToolCall(toolCallGuard.forRun(
                                triple.platform(), triple.botId(), triple.groupId()))
                        .build(),
                Map.copyOf(toolsByName),
                // 重放记账（T055）：is_replay=1 单独合计（SC-005 负向断言口径）
                new CostAwareStreamFn(streamFn, costRecorder,
                        new LlmCallMeta(triple.platform(), triple.botId(), triple.groupId(),
                                runId, "turn", "replay-" + caze.getId() + "-" + runId, true)),
                clock, idGenerator::nextId,
                (completedRunId, turnMessages) -> {
                    producedMessages.addAll(turnMessages);
                    sessionManager.appendTranscript(session, turnMessages, completedRunId);
                },
                loopEventWiring.newListener());
        AgentLoop.RunOutcome loopOutcome = agentLoop.execute(spec);

        List<String> speeches = toolset.sendShadow().speeches().stream()
                .map(ShadowSendMessage.ShadowSpeech::text).toList();
        List<String> createdTasks = toolset.taskShadow() == null
                ? List.of() : toolset.taskShadow().createdTasks();

        long latencyMs = Duration.between(
                LocalDateTime.ofInstant(spec.clock().instant(), java.time.ZoneOffset.UTC),
                LocalDateTime.now(clock)).toMillis();
        String inputDigest = scenario.triggerEvent().digest();
        String outputDigest = String.join(" / ", speeches);
        EvalSample sample = new EvalSample(caze.getId(), EvalResultDO.SOURCE_REPLAY,
                triple.platform(), triple.botId(), triple.groupId(),
                null, null, clip(inputDigest), clip(outputDigest));
        // 影子出口语义等价：发言被记录即「会被投递」；Run 失败形态（error/aborted）照实记
        SampleFacts facts = new SampleFacts(speeches.size(),
                latencyMs < 0 ? null : latencyMs,
                !"error".equals(loopOutcome.stopReason()));
        String narrative = narrativeOf(history, producedMessages, speeches);
        EvalInput input = new EvalInput(sample, rubric, narrative, facts);
        EvalOutcome code = codeRuleEvaluator.evaluate(input);
        EvalOutcome judged = agentJudgeEvaluator.evaluate(input);
        if (code.failed()) {
            return new SingleExecution(code, speeches, createdTasks, versionGroupOf(versionKeys));
        }
        if (judged.failed()) {
            return new SingleExecution(judged, speeches, createdTasks, versionGroupOf(versionKeys));
        }
        List<cn.edu.scut.chatbot.observability.eval.ItemVerdict> merged =
                new ArrayList<>(code.itemVerdicts());
        merged.addAll(judged.itemVerdicts());
        String explanation = judged.explanation()
                + (code.explanation() == null ? "" : "；" + code.explanation());
        RubricJudge.JudgeResult result = rubricJudge.judge(rubric, merged, explanation);
        EvalOutcome outcome = result.failed()
                ? EvalOutcome.failure(result.failureReason())
                : new EvalOutcome(result.score(), result.decision(), result.explanation(),
                judged.judgeModel(), null, merged);
        return new SingleExecution(outcome, speeches, createdTasks, versionGroupOf(versionKeys));
    }

    private EvalResultDO resultRow(long batchId, EvalCaseDO caze,
                                   ReplayGroupProvisioner.ReplayTriple triple,
                                   EvalOutcome outcome, String judgeModel) {
        EvalResultDO row = new EvalResultDO();
        row.setBatchId(batchId);
        row.setCaseId(caze.getId());
        row.setSourceKind(EvalResultDO.SOURCE_REPLAY);
        row.setPlatform(triple.platform());
        row.setBotId(triple.botId());
        row.setGroupId(triple.groupId());
        row.setScore(outcome.score().toJson());
        row.setDecision(outcome.decision());
        row.setExplanation(outcome.explanation());
        row.setJudgeModel(outcome.judgeModel() != null ? outcome.judgeModel() : judgeModel);
        return row;
    }

    /**
     * 版本分组键（注入前后对照的分组口径：版本三键拼接）
     */
    static String versionGroupOf(RunConfig.VersionKeys keys) {
        return keys == null ? "unknown"
                : keys.promptFragment() + "/" + keys.persona() + "/" + keys.slotBinding();
    }

    private String reportJson(int k, int passed, String versionGroupKey,
                              List<ReplayReport.ExecutionDetail> details) {
        ObjectNode report = JsonMappers.shared().createObjectNode();
        report.put("executions", k);
        report.put("passed", passed);
        report.put("passRate", k == 0 ? 0.0 : (double) passed / k);
        if (versionGroupKey != null) {
            report.put("versionGroupKey", versionGroupKey);
        }
        ArrayNode detailArray = report.putArray("details");
        for (ReplayReport.ExecutionDetail detail : details) {
            ObjectNode node = detailArray.addObject();
            node.put("attempt", detail.attempt());
            node.put("decision", detail.decision());
            node.set("speeches", JsonMappers.shared().valueToTree(detail.speeches()));
            node.set("createdTasks", JsonMappers.shared().valueToTree(detail.createdTasks()));
            node.put("failureReason", detail.failureReason());
        }
        return report.toString();
    }

    /**
     * 重放成本（is_replay=1 水位口径；按 k 次执行 × 每次 2 调用上限带界轮询等落账）
     */
    private String replayCost(long sinceId, int expectedMaxCalls) {
        LlmCallLogAggregation totals = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            totals = llmCallLogMapper.selectReplayTotalsSinceId(sinceId);
            if (totals != null && totals.getCalls() >= expectedMaxCalls) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ObjectNode cost = JsonMappers.shared().createObjectNode();
        if (totals != null) {
            cost.put("calls", totals.getCalls());
            cost.put("inputTokens", totals.getInputTokens());
            cost.put("outputTokens", totals.getOutputTokens());
            cost.put("cachedTokens", totals.getCachedTokens());
            cost.put("costAmount", totals.getCostAmount() == null
                    ? null : totals.getCostAmount().toPlainString());
        }
        return cost.toString();
    }

    /**
     * 重放轨迹叙事（判分输入：前情 + 触发 + 本轮产出投影）
     */
    private String narrativeOf(List<AgentMessage> history, List<AgentMessage> produced,
                               List<String> speeches) {
        StringBuilder builder = new StringBuilder("【重放前情与触发】\n");
        for (AgentMessage message : history) {
            builder.append("- ").append(briefOf(message)).append('\n');
        }
        builder.append("【本轮执行】\n");
        for (AgentMessage message : produced) {
            builder.append("- ").append(briefOf(message)).append('\n');
        }
        builder.append("【影子发言】\n");
        for (String speech : speeches) {
            builder.append("- ").append(speech).append('\n');
        }
        return builder.toString();
    }

    private String briefOf(AgentMessage message) {
        if (message instanceof UserMessage user) {
            StringBuilder text = new StringBuilder();
            for (var part : user.parts()) {
                if (part instanceof cn.edu.scut.chatbot.contract.message.TextPart textPart) {
                    text.append(textPart.text());
                }
            }
            return user.senderUserId() + ": " + text;
        }
        if (message instanceof cn.edu.scut.chatbot.loop.message.AssistantMessage assistant) {
            return "Agent: " + (assistant.textContent() == null ? "（工具轮）" : assistant.textContent());
        }
        if (message instanceof cn.edu.scut.chatbot.loop.message.ToolResultMessage toolResult) {
            return "工具 " + toolResult.toolName() + (toolResult.isError() ? " → 失败" : " → 完成");
        }
        return message.kind().name();
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1024 ? text : text.substring(0, 1024);
    }
}
