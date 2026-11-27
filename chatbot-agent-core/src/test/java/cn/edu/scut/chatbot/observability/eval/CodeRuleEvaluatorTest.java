// 守护纪律：Code 评估器判据边界（发言条数 / 时延 / outboxId 携带 / 未登记断言保守
// 失败 / 事实缺失按失败保守——T042，eval-and-flywheel 契约第 1 节）
package cn.edu.scut.chatbot.observability.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeRuleEvaluatorTest {

    private final CodeRuleEvaluator evaluator = new CodeRuleEvaluator();

    private static EvalInput input(List<RubricItem> rubric, SampleFacts facts) {
        return new EvalInput(new EvalSample(null, "daily", "qq", "bot01", "g1",
                1L, "trace-1", "输入", "输出"), rubric, null, facts);
    }

    private static RubricItem code(String code, Integer param) {
        return new RubricItem(code.equals(RubricItem.CODE_OUTBOX_CARRIED) ? "process"
                : code.equals(RubricItem.CODE_LATENCY_MS_MAX) ? "cost" : "form",
                "代码断言", RubricItem.KIND_CODE, false, code, param);
    }

    private EvalOutcome evaluate(List<RubricItem> rubric, SampleFacts facts) {
        return evaluator.evaluate(input(rubric, facts));
    }

    @Test
    void 发言条数_上限内通过_超限失败_param覆盖缺省() {
        List<RubricItem> rubric = List.of(code(RubricItem.CODE_SPEAK_COUNT_MAX, null));

        assertThat(evaluate(rubric, new SampleFacts(3, null, null)).itemVerdicts().get(0).passed())
                .isTrue();   // 缺省上限 3：3 条不超
        assertThat(evaluate(rubric, new SampleFacts(4, null, null)).itemVerdicts().get(0).passed())
                .isFalse();  // 4 条超缺省上限

        List<RubricItem> tighter = List.of(code(RubricItem.CODE_SPEAK_COUNT_MAX, 2));
        assertThat(evaluate(tighter, new SampleFacts(3, null, null)).itemVerdicts().get(0).passed())
                .isFalse();  // param=2 收紧
    }

    @Test
    void 时延上限_边界值通过_超时失败() {
        List<RubricItem> rubric = List.of(code(RubricItem.CODE_LATENCY_MS_MAX, 5_000));

        assertThat(evaluate(rubric, new SampleFacts(null, 5_000L, null)).itemVerdicts().get(0).passed())
                .isTrue();
        assertThat(evaluate(rubric, new SampleFacts(null, 5_001L, null)).itemVerdicts().get(0).passed())
                .isFalse();
    }

    @Test
    void outboxId携带_携带通过_未携带失败_事实缺失保守失败() {
        List<RubricItem> rubric = List.of(code(RubricItem.CODE_OUTBOX_CARRIED, null));

        assertThat(evaluate(rubric, new SampleFacts(null, null, true)).itemVerdicts().get(0).passed())
                .isTrue();
        assertThat(evaluate(rubric, new SampleFacts(null, null, false)).itemVerdicts().get(0).passed())
                .isFalse();
        ItemVerdict missing = evaluate(rubric, new SampleFacts(null, null, null)).itemVerdicts().get(0);
        assertThat(missing.passed()).isFalse();
        assertThat(missing.evidence()).contains("保守");
    }

    @Test
    void 事实缺失_条数与时延均按失败保守() {
        assertThat(evaluate(List.of(code(RubricItem.CODE_SPEAK_COUNT_MAX, null)),
                new SampleFacts(null, 1L, null)).itemVerdicts().get(0).passed()).isFalse();
        assertThat(evaluate(List.of(code(RubricItem.CODE_LATENCY_MS_MAX, null)),
                new SampleFacts(1, null, null)).itemVerdicts().get(0).passed()).isFalse();
    }

    @Test
    void 未登记断言标识_按失败保守并在证据点名() {
        ItemVerdict verdict = evaluate(List.of(code("unknownAssert", null)),
                new SampleFacts(1, 1L, true)).itemVerdicts().get(0);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.evidence()).contains("unknownAssert");
    }

    @Test
    void 混合rubric_judge项不评_code项单独成outcome() {
        List<RubricItem> rubric = List.of(
                code(RubricItem.CODE_SPEAK_COUNT_MAX, 3),
                new RubricItem("respond", "切题", RubricItem.KIND_JUDGE, false, null, null));

        EvalOutcome outcome = evaluate(rubric, new SampleFacts(2, 100L, true));

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.itemVerdicts()).hasSize(1);          // 只评了 code 项
        assertThat(outcome.judgeModel()).isNull();              // 无模型参与
        assertThat(outcome.score().form()).isEqualTo(100);
        assertThat(outcome.score().respond()).isNull();         // judge 项未被本评估器覆盖
        assertThat(outcome.explanation()).contains("代码断言");
    }
}
