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
            log.error("重放执行异常（caseId={}）：{}", caze.getId(), e.getMessage(), e);
            return new ReplayReport(0, caze.getId(), 0, 0, 0.0, null, List.of(), null,
                    "重放执行异常：" + e.getMessage());
        } finally {
            concurrencyGate.release();
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
