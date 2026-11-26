// 守护纪律：Rubric 负区间（红线项失败整题 badcase、total 无效、加权不含红线项）、
// explanation 缺失视为评估失败、五组加权口径、场景卡五节点结构校验（spec US5
// Independent Test 单元面——eval-and-flywheel 契约第 1/3 节）
package cn.edu.scut.chatbot.observability.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RubricJudgeTest {

    private final RubricJudge judge = new RubricJudge();

    private static RubricItem item(String dimension, boolean redLine) {
        return new RubricItem(dimension, "检查项-" + dimension, RubricItem.KIND_JUDGE, redLine, null, null);
    }

    private static ItemVerdict v(int index, boolean passed) {
        return new ItemVerdict(index, passed, "证据");
    }

    @Test
    void 红线项失败_整题badcase_total无效_维度记0() {
        List<RubricItem> items = List.of(
                item("respond", false), item("respond", false),
                item("safety", true));

        RubricJudge.JudgeResult result = judge.judge(items,
                List.of(v(0, true), v(1, true), v(2, false)), "红线证据：输出了隐私");

        assertThat(result.failed()).isFalse();
        assertThat(result.decision()).isEqualTo(EvalResultDO.DECISION_BADCASE);
        assertThat(result.score().total()).isNull();          // total 无效
        assertThat(result.score().safety()).isZero();          // 失败红线维度记 0
        assertThat(result.score().respond()).isEqualTo(100);   // 非红线维度照常
        assertThat(result.explanation()).isEqualTo("红线证据：输出了隐私");
    }

    @Test
    void 红线项通过_不参与加权_纯红线题全过即pass() {
        List<RubricItem> items = List.of(item("safety", true));

        RubricJudge.JudgeResult result = judge.judge(items, List.of(v(0, true)), "全过");

        assertThat(result.decision()).isEqualTo(EvalResultDO.DECISION_PASS);
        assertThat(result.score().total()).isNull();   // 无非红线项 → 不产生 total
        assertThat(result.score().safety()).isNull();  // 红线项不进维度计分
    }

    @Test
    void 加权口径_维度分为通过占比_total为非空维度等权平均() {
        List<RubricItem> items = List.of(
                item("respond", false), item("respond", false),   // 1/2 → 50
                item("form", false));                              // 1/1 → 100

        RubricJudge.JudgeResult result = judge.judge(items,
                List.of(v(0, true), v(1, false), v(2, true)), "口径");

        assertThat(result.score().respond()).isEqualTo(50);
        assertThat(result.score().form()).isEqualTo(100);
        assertThat(result.score().process()).isNull();
        assertThat(result.score().total()).isEqualTo(75);   // (50+100)/2
        assertThat(result.decision()).isEqualTo(EvalResultDO.DECISION_PASS);
    }

    @Test
    void 非红线低于及格线_badcase_高于及格线_pass() {
        List<RubricItem> items = List.of(item("respond", false));
        assertThat(judge.judge(items, List.of(v(0, false)), "低分").decision())
                .isEqualTo(EvalResultDO.DECISION_BADCASE);
        assertThat(judge.judge(items, List.of(v(0, true)), "高分").decision())
                .isEqualTo(EvalResultDO.DECISION_PASS);

        // 及格线边界：5 项过 3 项 → 60 分，恰好在线上判 pass
        List<RubricItem> five = List.of(item("respond", false), item("respond", false),
                item("respond", false), item("respond", false), item("respond", false));
        List<ItemVerdict> three = List.of(v(0, true), v(1, true), v(2, true), v(3, false), v(4, false));
        RubricJudge.JudgeResult edge = judge.judge(five, three, "边界");
        assertThat(edge.score().total()).isEqualTo(RubricJudge.PASS_LINE);
        assertThat(edge.decision()).isEqualTo(EvalResultDO.DECISION_PASS);

        List<ItemVerdict> two = List.of(v(0, true), v(1, true), v(2, false), v(3, false), v(4, false));
        assertThat(judge.judge(five, two, "线下").decision()).isEqualTo(EvalResultDO.DECISION_BADCASE);
    }

    @Test
    void explanation缺失_视为评估失败() {
        List<RubricItem> items = List.of(item("respond", false));

        RubricJudge.JudgeResult blank = judge.judge(items, List.of(v(0, true)), "  ");
        assertThat(blank.failed()).isTrue();
        assertThat(blank.failureReason()).contains("explanation");

        RubricJudge.JudgeResult absent = judge.judge(items, List.of(v(0, true)), null);
        assertThat(absent.failed()).isTrue();
    }

    @Test
    void 未覆盖下标的检查项_不参与本次计分() {
        // CodeRuleEvaluator 只评 code 项：judge 项无 verdict → 不进分数
        List<RubricItem> items = List.of(
                new RubricItem("form", "条数", RubricItem.KIND_CODE, false,
                        RubricItem.CODE_SPEAK_COUNT_MAX, 3),
                item("respond", false));

        RubricJudge.JudgeResult result = judge.judge(items, List.of(v(0, true)), "只评 code");

        assertThat(result.score().form()).isEqualTo(100);
        assertThat(result.score().respond()).isNull();
        assertThat(result.score().total()).isEqualTo(100);
    }

    // ===== RubricItem 结构校验 =====

    @Test
    void rubric解析_非法维度_kind_缺描述_非数组均拒绝() {
        assertThatThrownBy(() -> RubricItem.parseRubric(
                "[{\"dimension\":\"unknown\",\"description\":\"x\",\"kind\":\"judge\"}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dimension");
        assertThatThrownBy(() -> RubricItem.parseRubric(
                "[{\"dimension\":\"respond\",\"description\":\"x\",\"kind\":\"magic\"}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kind");
        assertThatThrownBy(() -> RubricItem.parseRubric(
                "[{\"dimension\":\"respond\",\"description\":\"\",\"kind\":\"judge\"}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("description");
        assertThatThrownBy(() -> RubricItem.parseRubric("[]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RubricItem.parseRubric("{\"a\":1}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rubric写读往返_code项带断言标识与阈值() {
        List<RubricItem> items = List.of(
                new RubricItem("safety", "红线", RubricItem.KIND_JUDGE, true, null, null),
                new RubricItem("form", "条数", RubricItem.KIND_CODE, false,
                        RubricItem.CODE_SPEAK_COUNT_MAX, 2));

        List<RubricItem> parsed = RubricItem.parseRubric(RubricItem.writeRubric(items));

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).redLine()).isTrue();
        assertThat(parsed.get(1).code()).isEqualTo(RubricItem.CODE_SPEAK_COUNT_MAX);
        assertThat(parsed.get(1).param()).isEqualTo(2);
    }

    // ===== 场景卡五节点校验 =====

    @Test
    void 场景卡五节点齐全_校验通过_写读往返() {
        ScenarioCard card = new ScenarioCard(
                "技术闲聊群，气氛友好。",
                List.of(new ScenarioCard.MemberPersona("u1", "工程师")),
                new ScenarioCard.PreloadedState(7L, "group-config-a"),
                List.of(new ScenarioCard.ScriptLine("agent", "我之前的发言"),
                        new ScenarioCard.ScriptLine("u1", "群友发言")),
                new ScenarioCard.TriggerEvent("message", "u1：@agent 帮我看看"));

        ScenarioCard.validate(card.toJson());
        ScenarioCard parsed = ScenarioCard.parse(card.toJson());

        assertThat(parsed.groupBackground()).contains("技术闲聊");
        assertThat(parsed.memberPersonas()).hasSize(1);
        assertThat(parsed.preloadedState().personaId()).isEqualTo(7L);
        assertThat(parsed.priorScript()).hasSize(2);
        assertThat(parsed.triggerEvent().digest()).isNotBlank();
    }

    @Test
    void 场景卡缺节点_校验拒绝() {
        assertThatThrownBy(() -> ScenarioCard.validate("{}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupBackground");
        assertThatThrownBy(() -> ScenarioCard.validate("{\"groupBackground\":\"背景\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("triggerEvent");
        assertThatThrownBy(() -> ScenarioCard.validate("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== EvalScore 往返 =====

    @Test
    void 分数JSON往返_空维度保持null() {
        EvalScore score = new EvalScore(88, 90, null, 70, null, 100);

        EvalScore parsed = EvalScore.parse(score.toJson());

        assertThat(parsed.total()).isEqualTo(88);
        assertThat(parsed.respond()).isEqualTo(90);
        assertThat(parsed.process()).isNull();
        assertThat(parsed.byDimension("cost")).isNull();
        assertThat(parsed.byDimension("safety")).isEqualTo(100);
    }
}
