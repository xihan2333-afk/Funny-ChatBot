package cn.edu.scut.chatbot.observability.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Code 评估器（eval-and-flywheel 契约第 1 节、T042）：纯代码断言判分——
 * rubric 中 kind=code 的检查项按 {@link RubricItem} 的 code 断言标识与
 * {@link SampleFacts} 事实一一对应判定，judge 项不评（不参与本次计分）。
 *
 * <p>断言字典（与 RubricItem.CODE_* 一一对应，缺省阈值写成常量便于测试对齐）：
 * <ul>
 *   <li>speakCountMax——发言条数 ≤ param（缺省 3，防刷屏形态纪律）；</li>
 *   <li>latencyMsMax——端到端时延 ≤ param 毫秒（缺省 60000）；</li>
 *   <li>outboxCarried——出事件携带 outboxId（回执可结算，事实缺省按失败保守）。</li>
 * </ul>
 * 事实字段为 null（不可得）时对应断言按失败保守处理并在 evidence 说明——
 * 宁可误报坏样本进人工归因，不可放过真坏样本。
 */
@Component
public class CodeRuleEvaluator implements Evaluator {

    /**
     * 发言条数缺省上限（形态纪律：人格配置缺失时的兜底口径）
     */
    public static final int DEFAULT_SPEAK_COUNT_MAX = 3;

    /**
     * 端到端时延缺省上限（毫秒）
     */
    public static final long DEFAULT_LATENCY_MS_MAX = 60_000L;

    @Override
    public EvalOutcome evaluate(EvalInput input) {
        List<RubricItem> items = input.rubric();
        List<ItemVerdict> verdicts = new ArrayList<>();
        List<String> passedNotes = new ArrayList<>();
        List<String> failedNotes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            RubricItem item = items.get(i);
            if (!RubricItem.KIND_CODE.equals(item.kind())) {
                continue;
            }
            AssertionResult result = assertItem(item, input.facts());
            verdicts.add(new ItemVerdict(i, result.passed(), result.evidence()));
            if (result.passed()) {
                passedNotes.add(item.description() + "：" + result.evidence());
            } else {
                failedNotes.add(item.description() + "：" + result.evidence());
            }
        }
        StringBuilder explanation = new StringBuilder("代码断言：");
        explanation.append("通过 ").append(passedNotes.size()).append(" 项");
        if (!passedNotes.isEmpty()) {
            explanation.append("（").append(String.join("；", passedNotes)).append("）");
        }
        if (!failedNotes.isEmpty()) {
            explanation.append("；失败 ").append(failedNotes.size()).append(" 项（")
                    .append(String.join("；", failedNotes)).append("）");
        }
        RubricJudge.JudgeResult judged = new RubricJudge().judge(items, verdicts, explanation.toString());
        // Code 判分无模型参与——judgeModel 留空
        return new EvalOutcome(judged.score(), judged.decision(), judged.explanation(),
                null, null, verdicts);
    }

    private AssertionResult assertItem(RubricItem item, SampleFacts facts) {
        String code = item.code() == null ? "" : item.code();
        switch (code) {
            case RubricItem.CODE_SPEAK_COUNT_MAX: {
                int limit = item.param() != null ? item.param() : DEFAULT_SPEAK_COUNT_MAX;
                if (facts.speakCount() == null) {
                    return new AssertionResult(false, "发言条数不可得（按失败保守）");
                }
                return new AssertionResult(facts.speakCount() <= limit,
                        "发言 " + facts.speakCount() + " 条，上限 " + limit);
            }
            case RubricItem.CODE_LATENCY_MS_MAX: {
                long limit = item.param() != null ? item.param() : DEFAULT_LATENCY_MS_MAX;
                if (facts.latencyMs() == null) {
                    return new AssertionResult(false, "端到端时延不可得（按失败保守）");
                }
                return new AssertionResult(facts.latencyMs() <= limit,
                        "时延 " + facts.latencyMs() + "ms，上限 " + limit + "ms");
            }
            case RubricItem.CODE_OUTBOX_CARRIED: {
                if (facts.outboxCarried() == null) {
                    return new AssertionResult(false, "outboxId 携带情况不可得（按失败保守）");
                }
                return new AssertionResult(facts.outboxCarried(),
                        facts.outboxCarried() ? "出事件携带 outboxId" : "出事件未携带 outboxId");
            }
            default:
                // 未登记的断言标识：按失败保守并在证据中点名（评测集修订面可见）
                return new AssertionResult(false, "未登记的 code 断言：" + code);
        }
    }

    private record AssertionResult(boolean passed, String evidence) {
    }
}
