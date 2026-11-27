package cn.edu.scut.chatbot.observability.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Rubric 判分引擎（eval-and-flywheel 契约第 1 节、FR-008）：题目级二元检查项 →
 * 五组分数 + 判定。纯函数无状态——两类评估器的同一判分语义收口点。
 *
 * <p>口径（SOTOPIA 负区间 + 五组加权平均）：
 * <ul>
 *   <li>维度分 = 该维度内<b>非红线</b>检查项的通过占比 ×100（维度无非红线项时为 null
 *       ——不参与 total 加权）；</li>
 *   <li>total = 非空维度分的等权平均（五组加权平均的缺省权重 1:1:1:1:1）；</li>
 *   <li>红线项任何一项失败 → 整题 decision=badcase、total 无效（null）、含失败红线
 *       的维度记 0、explanation 必须引用证据——红线项无论通过与否都不参与加权平均；</li>
 *   <li>非红线情形 decision=badcase 当且仅当 total 低于及格线（无任何非红线项的
 *       纯红线题，红线全过即 pass）；</li>
 *   <li>explanation 缺失视为评估失败（failed=true）——该条目进批次 report 的
 *       failed 列表，不落 eval_result（分数必须可解释）。</li>
 * </ul>
 *
 * <p>未提供判定的检查项（verdicts 未覆盖的下标）视为「本评估器不评」——不参与
 * 计分：CodeRuleEvaluator 只评 code 项、AgentJudgeEvaluator 只评 judge 项，
 * 批次侧合并两类判定后覆盖全部检查项再出最终结果。
 */
@Component
public class RubricJudge {

    /**
     * 非红线及格线：total 低于此值判 badcase（体验类阈值，非红线不一票否决）
     */
    public static final int PASS_LINE = 60;

    /**
     * 判分：检查项 + 各评估器已产出的二元判定 + 解释 → 分数与判定。
     */
    public JudgeResult judge(List<RubricItem> items, List<ItemVerdict> verdicts, String explanation) {
        if (explanation == null || explanation.isBlank()) {
            return JudgeResult.failed("explanation 缺失——分数必须可解释");
        }
        boolean[] passed = new boolean[items.size()];
        boolean[] judged = new boolean[items.size()];
        for (ItemVerdict verdict : verdicts) {
            if (verdict.index() >= 0 && verdict.index() < items.size()) {
                judged[verdict.index()] = true;
                passed[verdict.index()] = verdict.passed();
            }
        }

        boolean redLineFailed = false;
        List<String> failedRedLineDimensions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            RubricItem item = items.get(i);
            if (item.redLine() && judged[i] && !passed[i]) {
                redLineFailed = true;
                failedRedLineDimensions.add(item.dimension());
            }
        }

        Integer respond = dimensionScore(items, judged, passed, "respond", redLineFailed, failedRedLineDimensions);
        Integer process = dimensionScore(items, judged, passed, "process", redLineFailed, failedRedLineDimensions);
        Integer form = dimensionScore(items, judged, passed, "form", redLineFailed, failedRedLineDimensions);
        Integer cost = dimensionScore(items, judged, passed, "cost", redLineFailed, failedRedLineDimensions);
        Integer safety = dimensionScore(items, judged, passed, "safety", redLineFailed, failedRedLineDimensions);

        Integer total = null;
        if (!redLineFailed) {
            List<Integer> scores = new ArrayList<>();
            for (Integer value : java.util.Arrays.asList(respond, process, form, cost, safety)) {
                if (value != null) {
                    scores.add(value);
                }
            }
            if (!scores.isEmpty()) {
                long sum = 0;
                for (int value : scores) {
                    sum += value;
                }
                total = (int) Math.round((double) sum / scores.size());
            }
        }

        String decision;
        if (redLineFailed) {
            decision = EvalResultDO.DECISION_BADCASE;
        } else {
            decision = total == null || total >= PASS_LINE
                    ? EvalResultDO.DECISION_PASS : EvalResultDO.DECISION_BADCASE;
        }
        return new JudgeResult(new EvalScore(total, respond, process, form, cost, safety),
                decision, explanation, false, null);
    }

    /**
     * 维度分：非红线已评项通过占比 ×100；红线失败命中的维度记 0（负区间可见性）；
     * 无非红线已评项返回 null（不参与加权）
     */
    private Integer dimensionScore(List<RubricItem> items, boolean[] judged, boolean[] passed,
                                   String dimension, boolean redLineFailed, List<String> failedRedLineDimensions) {
        if (redLineFailed && failedRedLineDimensions.contains(dimension)) {
            return 0;
        }
        int totalCount = 0;
        int passedCount = 0;
        for (int i = 0; i < items.size(); i++) {
            RubricItem item = items.get(i);
            if (!dimension.equals(item.dimension()) || item.redLine() || !judged[i]) {
                continue;
            }
            totalCount++;
            if (passed[i]) {
                passedCount++;
            }
        }
        return totalCount == 0 ? null : (int) Math.round(passedCount * 100.0 / totalCount);
    }

    /**
     * 判分结果：failed=true 表示评估失败（不落 eval_result，进批次 report failed 列表）
     */
    public record JudgeResult(EvalScore score, String decision, String explanation,
                              boolean failed, String failureReason) {

        static JudgeResult failed(String reason) {
            return new JudgeResult(null, null, null, true, reason);
        }
    }
}
