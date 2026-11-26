package cn.edu.scut.chatbot.observability.eval;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogAggregation;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 评估任务实现（eval-and-flywheel 契约第 2 节、T045/T046）：批次状态机
 * running → done | failed，后台命名虚拟线程逐样本执行——抽样圈选（每群时间窗内
 * 终态 Turn 最近优先）→ 轨迹组装 → Code + Agent 两评估器 → RubricJudge 合并
 * 判分 → eval_result 落库；盲评比例标记 source_kind=blind。
 *
 * <p>纪律：
 * <ul>
 *   <li>并发上限 observe.replay.maxConcurrent（评估与重放共用同一道闸）——调度侧
 *       占满跳过本周期，手动侧抛 observe.eval_busy；</li>
 *   <li>eval_judge 槽位未绑定：批次直接 failed（report 载原因）不空转——告警面由
 *       内置「槽位缺失」规则（ConfigMissingProbe 周期探查）覆盖，此处不重复发通知；</li>
 *   <li>单条失败不重试记 report（failed 列表：turnId + 原因），批次照常走完；
 *       全部样本失败且零产出时批次判 failed（防「空转即成功」假象）；</li>
 *   <li>批次成本：llm_call_log 按 run_kind='eval' 时间窗汇总进 cost_stat JSON
 *       （评估调用经 LlmCallMeta.of("eval", …) 落账，独立于线上口径）；</li>
 *   <li>每日窗口 = [now-24h, now)，圈选范围 = 全部在服务群。</li>
 * </ul>
 */
@Service
public class EvalTaskServiceImpl implements EvalTaskService {

    private static final Logger log = LoggerFactory.getLogger(EvalTaskServiceImpl.class);

    /**
     * 每日抽样的回看窗口（小时）
     */
    static final int DAILY_WINDOW_HOURS = 24;

    /**
     * 评估调用在 llm_call_log 的 run_kind 标记（cost_stat 汇总口径）
     */
    static final String EVAL_RUN_KIND = "eval";

    private final EvalBatchMapper batchMapper;
    private final EvalResultMapper resultMapper;
    private final TurnMapper turnMapper;
    private final GroupMapper groupMapper;
    private final TrajectoryAssembler trajectoryAssembler;
    private final CodeRuleEvaluator codeRuleEvaluator;
    private final AgentJudgeEvaluator agentJudgeEvaluator;
    private final RubricJudge rubricJudge;
    private final LlmCallLogMapper llmCallLogMapper;
    private final SystemParamService params;
    private final Clock clock;

    /**
     * 评估与重放共用并发闸（observe.replay.maxConcurrent 同闸语义）
     */
    private final cn.edu.scut.chatbot.observability.replay.ReplayConcurrencyGate concurrencyGate;

    /**
     * 盲评抽签（可测试替换——IT 用固定序列断言五分之一标记）
     */
    private volatile DoubleSupplier blindDice = () -> ThreadLocalRandom.current().nextDouble();

    public EvalTaskServiceImpl(EvalBatchMapper batchMapper,
                               EvalResultMapper resultMapper,
                               TurnMapper turnMapper,
                               GroupMapper groupMapper,
                               TrajectoryAssembler trajectoryAssembler,
                               CodeRuleEvaluator codeRuleEvaluator,
                               AgentJudgeEvaluator agentJudgeEvaluator,
                               RubricJudge rubricJudge,
                               LlmCallLogMapper llmCallLogMapper,
                               SystemParamService params,
                               Clock clock,
                               cn.edu.scut.chatbot.observability.replay.ReplayConcurrencyGate concurrencyGate) {
        this.batchMapper = batchMapper;
        this.resultMapper = resultMapper;
        this.turnMapper = turnMapper;
        this.groupMapper = groupMapper;
        this.trajectoryAssembler = trajectoryAssembler;
        this.codeRuleEvaluator = codeRuleEvaluator;
        this.agentJudgeEvaluator = agentJudgeEvaluator;
        this.rubricJudge = rubricJudge;
        this.llmCallLogMapper = llmCallLogMapper;
        this.params = params;
        this.clock = clock;
        this.concurrencyGate = concurrencyGate;
    }

    /**
     * 测试替换盲评抽签（固定序列断言 blind 标记比例与位置）
     */
    void overrideBlindDice(DoubleSupplier dice) {
        this.blindDice = dice;
    }

    @Override
    public EvalBatchView startDaily() {
        if (!tryAcquireSlot()) {
            log.warn("每日评估跳过本周期：批次并发已达上限（observe.replay.maxConcurrent）");
            return null;
        }
        try {
            LocalDateTime to = LocalDateTime.now(clock);
            LocalDateTime from = to.minusHours(DAILY_WINDOW_HOURS);
            int samplePerGroup = params.getInt("observe.eval.dailySamplePerGroup", 20);
            ObjectNode scope = JsonMappers.shared().createObjectNode();
            scope.put("batchKind", EvalBatchDO.KIND_DAILY);
            scope.put("from", from.toString());
            scope.put("to", to.toString());
            scope.put("samplePerGroup", samplePerGroup);
            return start(EvalBatchDO.KIND_DAILY, scope.toString(),
                    collectSamples(from, to, null, samplePerGroup));
        } catch (Exception e) {
            concurrencyGate.release();
            throw e;
        }
    }

    @Override
    public EvalBatchView startManual(EvalScope evalScope) {
        if (evalScope == null || evalScope.from() == null || evalScope.to() == null
                || !evalScope.to().isAfter(evalScope.from())) {
            throw new IllegalArgumentException("手动圈选必须提供有效时间范围（from < to）");
        }
        if (!tryAcquireSlot()) {
            throw new NonRetryableException(ErrorCodes.OBSERVE_EVAL_BUSY,
                    "评估批次并发已达上限（observe.replay.maxConcurrent），稍后重试");
        }
        try {
            int samplePerGroup = evalScope.samplePerGroup() != null ? evalScope.samplePerGroup()
                    : params.getInt("observe.eval.dailySamplePerGroup", 20);
            ObjectNode scope = JsonMappers.shared().createObjectNode();
            scope.put("batchKind", EvalBatchDO.KIND_MANUAL);
            scope.put("from", evalScope.from().toString());
            scope.put("to", evalScope.to().toString());
            if (evalScope.platform() != null) {
                scope.put("platform", evalScope.platform());
            }
            if (evalScope.botId() != null) {
                scope.put("botId", evalScope.botId());
            }
            if (evalScope.groupId() != null) {
                scope.put("groupId", evalScope.groupId());
            }
            scope.put("samplePerGroup", samplePerGroup);
            GroupDO only = evalScope.platform() != null && evalScope.groupId() != null
                    ? groupMapper.selectByGroup(evalScope.platform(), evalScope.botId(), evalScope.groupId())
                    : null;
            return start(EvalBatchDO.KIND_MANUAL, scope.toString(),
                    collectSamples(evalScope.from(), evalScope.to(), only, samplePerGroup));
        } catch (Exception e) {
            concurrencyGate.release();
            throw e;
        }
    }

    @Override
    public void submitBlindScore(long resultId, BlindScore score) {
        if (score == null || score.score() == null) {
            throw new IllegalArgumentException("盲评打分必须提供分数");
        }
        EvalResultDO machine = resultMapper.selectById(resultId);
        if (machine == null) {
            throw new IllegalArgumentException("评估结果不存在：" + resultId);
        }
        if (!EvalResultDO.SOURCE_BLIND.equals(machine.getSourceKind()) || machine.getJudgeModel() == null) {
            throw new IllegalArgumentException("仅盲评机器结果（source_kind=blind 且有 judge_model）可回填人工打分");
        }
        EvalResultDO human = new EvalResultDO();
        human.setBatchId(machine.getBatchId());
        human.setCaseId(machine.getCaseId());
        human.setSourceKind(EvalResultDO.SOURCE_BLIND);
        human.setPlatform(machine.getPlatform());
        human.setBotId(machine.getBotId());
        human.setGroupId(machine.getGroupId());
        human.setTurnId(machine.getTurnId());
        human.setTraceId(machine.getTraceId());
        human.setScore(score.score().toJson());
        human.setDecision(score.decision() == null ? EvalResultDO.DECISION_PASS : score.decision());
        human.setExplanation(score.explanation());
        human.setAttribution(null);
        human.setJudgeModel(null);
        resultMapper.insert(human);
    }

    @Override
    public EvalBatchView getBatch(long batchId) {
        EvalBatchDO batch = batchMapper.selectById(batchId);
        return batch == null ? null : EvalBatchView.from(batch);
    }

    @Override
    public List<EvalBatchView> listBatches(String batchKind, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        List<EvalBatchView> views = new ArrayList<>();
        for (EvalBatchDO batch : batchMapper.selectPage(batchKind, safePage * safeSize, safeSize)) {
            views.add(EvalBatchView.from(batch));
        }
        return views;
    }

    @Override
    public List<EvalResultView> listResults(long batchId) {
        List<EvalResultView> views = new ArrayList<>();
        for (EvalResultDO result : resultMapper.selectByBatch(batchId)) {
            views.add(EvalResultView.from(result));
        }
        return views;
    }

    // ===== 批次执行 =====

    private boolean tryAcquireSlot() {
        return concurrencyGate.tryAcquire();
    }

    /**
     * 圈选样本：全部在服务群（only 非空时只圈该群）× 时间窗 × 每群上限
     */
    private List<SampleUnit> collectSamples(LocalDateTime from, LocalDateTime to,
                                            GroupDO only, int samplePerGroup) {
        List<SampleUnit> units = new ArrayList<>();
        double blindRatio = Double.parseDouble(
                params.getString("observe.eval.blindRatio", "0.2"));
        List<GroupDO> groups = only != null ? List.of(only) : groupMapper.selectAllInService();
        for (GroupDO group : groups) {
            List<TurnDO> turns = turnMapper.selectTerminalByGroupWindow(
                    group.getPlatform(), group.getBotId(), group.getGroupId(),
                    from, to, samplePerGroup);
            for (TurnDO turn : turns) {
                units.add(new SampleUnit(turn, blindDice.getAsDouble() < blindRatio));
            }
        }
        return units;
    }

    /**
     * 建批次（槽位预检失败直接落 failed 批次）并派发后台执行。
     * cost_stat 用 llm_call_log 行 id 水位圈定本批落账——落账 created_at 是 DB 时钟，
     * 应用侧时间窗会与部署时区错位（测试机 UTC vs 系统 CST 差 8 小时），水位法免疫。
     */
    private EvalBatchView start(String kind, String scopeJson, List<SampleUnit> units) {
        String judgeModel;
        try {
            judgeModel = agentJudgeEvaluator.judgeModelOf();
        } catch (Exception e) {
            EvalBatchDO failed = new EvalBatchDO();
            failed.setBatchKind(kind);
            failed.setScope(scopeJson);
            failed.setStatus(EvalBatchDO.STATUS_RUNNING);
            batchMapper.insert(failed);
            batchMapper.finish(failed.getId(), EvalBatchDO.STATUS_FAILED,
                    failureReport("eval_judge 槽位未绑定或不可用：" + e.getMessage()), null,
                    LocalDateTime.now(clock));
            log.error("评估批次直接 failed：eval_judge 槽位不可用（{}）", e.getMessage());
            return EvalBatchView.from(batchMapper.selectById(failed.getId()));
        }
        Long watermark = llmCallLogMapper.selectMaxIdByRunKind(EVAL_RUN_KIND);
        EvalBatchDO batch = new EvalBatchDO();
        batch.setBatchKind(kind);
        batch.setScope(scopeJson);
        batch.setStatus(EvalBatchDO.STATUS_RUNNING);
        batchMapper.insert(batch);
        long batchId = batch.getId();
        Thread.ofVirtual().name("eval-batch-" + batchId).start(() ->
                runBatch(batchId, units, judgeModel, watermark == null ? 0L : watermark));
        return EvalBatchView.from(batch);
    }

    private void runBatch(long batchId, List<SampleUnit> units, String judgeModel, long costWatermark) {
        int evaluated = 0;
        int passed = 0;
        int blind = 0;
        ArrayNode failedList = JsonMappers.shared().createArrayNode();
        try {
            for (SampleUnit unit : units) {
                TurnDO turn = unit.turn();
                try {
                    SampleOutcome sample = evaluateOne(turn, unit.blind(), judgeModel);
                    EvalOutcome outcome = sample.outcome();
                    if (outcome.failed()) {
                        failedList.addObject().put("turnId", turn.getId())
                                .put("reason", outcome.failureReason());
                        continue;
                    }
                    insertResult(batchId, turn, unit.blind(), outcome, sample.traceId(), null);
                    evaluated++;
                    if (EvalResultDO.DECISION_PASS.equals(outcome.decision())) {
                        passed++;
                    }
                    if (unit.blind()) {
                        blind++;
                    }
                } catch (Exception e) {
                    // 单条失败不重试记 report（契约第 2 节）
                    log.warn("评估样本失败（turnId={}）：{}", turn.getId(), e.getMessage());
                    failedList.addObject().put("turnId", turn.getId())
                            .put("reason", String.valueOf(e.getMessage()));
                }
            }
            String status = evaluated == 0 && failedList.size() > 0
                    ? EvalBatchDO.STATUS_FAILED : EvalBatchDO.STATUS_DONE;
            batchMapper.finish(batchId, status,
                    report(evaluated, passed, blind, units.size(), failedList),
                    costStat(costWatermark, units.size()), LocalDateTime.now(clock));
        } catch (Exception e) {
            // 批次级意外异常：failed 收口，不让线程带异常静默消亡
            log.error("评估批次 {} 执行异常", batchId, e);
            batchMapper.finish(batchId, EvalBatchDO.STATUS_FAILED,
                    failureReport("批次执行异常：" + e.getMessage()), null, LocalDateTime.now(clock));
        } finally {
            concurrencyGate.release();
        }
    }

    /**
     * 单样本评估产出：判分结果 + 触发器 traceparent 解析出的 traceId——后者是
     * eval_result.trace_id 落库取数来源（评估结果与执行 trace 的关联键）
     */
    record SampleOutcome(EvalOutcome outcome, String traceId) {
    }

    /**
     * 单样本评估：轨迹组装 → 两评估器 → RubricJudge 合并（red-line 与加权口径收口）
     */
    private SampleOutcome evaluateOne(TurnDO turn, boolean isBlind, String judgeModel) {
        TrajectoryAssembler.TrajectoryView view = trajectoryAssembler.assembleTurn(turn.getId());
        List<RubricItem> rubric = RubricItem.defaultOnlineRubric();
        EvalSample sample = new EvalSample(null,
                isBlind ? EvalResultDO.SOURCE_BLIND : EvalResultDO.SOURCE_DAILY,
                turn.getPlatform(), turn.getBotId(), turn.getGroupId(),
                turn.getId(), view.traceId(), view.inputDigest(), view.outputDigest());
        EvalInput input = new EvalInput(sample, rubric, view.narrative(), factsOf(turn, view));
        EvalOutcome code = codeRuleEvaluator.evaluate(input);
        EvalOutcome judged = agentJudgeEvaluator.evaluate(input);
        if (code.failed()) {
            return new SampleOutcome(code, view.traceId());
        }
        if (judged.failed()) {
            return new SampleOutcome(judged, view.traceId());
        }
        List<ItemVerdict> merged = new ArrayList<>(code.itemVerdicts());
        merged.addAll(judged.itemVerdicts());
        String explanation = judged.explanation()
                + (code.explanation() == null ? "" : "；" + code.explanation());
        RubricJudge.JudgeResult result = rubricJudge.judge(rubric, merged, explanation);
        if (result.failed()) {
            return new SampleOutcome(EvalOutcome.failure(result.failureReason()), view.traceId());
        }
        return new SampleOutcome(new EvalOutcome(result.score(), result.decision(), result.explanation(),
                judgeModel, null, merged), view.traceId());
    }

    private SampleFacts factsOf(TurnDO turn, TrajectoryAssembler.TrajectoryView view) {
        Long latencyMs = null;
        if (turn.getAcceptedAt() != null && turn.getFinishedAt() != null) {
            latencyMs = Duration.between(turn.getAcceptedAt(), turn.getFinishedAt()).toMillis();
        }
        boolean outboxCarried = turn.getOutboxIds() != null
                && !turn.getOutboxIds().isBlank()
                && !"[]".equals(turn.getOutboxIds().trim());
        return new SampleFacts(view.speakCount(), latencyMs, outboxCarried);
    }

    private void insertResult(long batchId, TurnDO turn, boolean isBlind,
                              EvalOutcome outcome, String traceId, Long caseId) {
        EvalResultDO row = new EvalResultDO();
        row.setBatchId(batchId);
        row.setCaseId(caseId);
        row.setSourceKind(isBlind ? EvalResultDO.SOURCE_BLIND : EvalResultDO.SOURCE_DAILY);
        row.setPlatform(turn.getPlatform());
        row.setBotId(turn.getBotId());
        row.setGroupId(turn.getGroupId());
        row.setTurnId(turn.getId());
        row.setTraceId(traceId);
        row.setScore(outcome.score().toJson());
        row.setDecision(outcome.decision());
        row.setExplanation(outcome.explanation());
        row.setJudgeModel(outcome.judgeModel());
        resultMapper.insert(row);
    }

    private String report(int evaluated, int passed, int blind, int sampled, ArrayNode failedList) {
        ObjectNode report = JsonMappers.shared().createObjectNode();
        report.put("sampled", sampled);
        report.put("evaluated", evaluated);
        report.put("passed", passed);
        report.put("badcase", evaluated - passed);
        report.put("blind", blind);
        report.set("failed", failedList);
        return report.toString();
    }

    private String failureReport(String reason) {
        ObjectNode report = JsonMappers.shared().createObjectNode();
        report.put("failureReason", reason);
        return report.toString();
    }

    /**
     * 批次成本：水位 id 之后 run_kind='eval' 的落账汇总。计量经 CostRecorder 异步
     * 批量落库（≤500ms 窗口）——按期望次数带上限轮询，等最后一批落库再收口
     * （后台低频批次线程，毫秒级宽限换取 cost_stat 完整性）。
     */
    private String costStat(long costWatermark, int expectedCalls) {
        LlmCallLogAggregation totals = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            totals = llmCallLogMapper.selectTotalsByRunKindSinceId(EVAL_RUN_KIND, costWatermark);
            if (totals != null && totals.getCalls() >= expectedCalls) {
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
     * 圈选出的样本单元：Turn + 是否盲评
     */
    record SampleUnit(TurnDO turn, boolean blind) {
    }
}
