// 守护纪律：eval_judge 结构化 JSON 判分解析（剧本正常解析落 outcome、漏项保守失败、
// 非法 JSON 与 explanation 缺失判评估失败、低温度强制 JSON 参数——T044，
// eval-and-flywheel 契约第 1 节；模型走假件剧本不进真模型）
package cn.edu.scut.chatbot.observability.eval;

import cn.edu.scut.chatbot.llm.gateway.CallOnceResult;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.gateway.ModelGateway;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.SlotResolver;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.ModelConfig;
import cn.edu.scut.chatbot.loop.ModelRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentJudgeEvaluatorTest {

    private ModelGateway modelGateway;
    private SlotResolver slotResolver;
    private AgentJudgeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        modelGateway = mock(ModelGateway.class);
        slotResolver = mock(SlotResolver.class);
        when(slotResolver.resolve(ModelCatalog.SLOT_EVAL_JUDGE)).thenReturn(new ModelConfig(
                new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "provider-a", "judge-model-x",
                        "http://upstream", null, null, ModelCatalog.SLOT_EVAL_JUDGE),
                new GenerationParams(0.8, 2048, false, Map.of())));
        evaluator = new AgentJudgeEvaluator(modelGateway, slotResolver,
                new RubricJudge(), Clock.systemUTC());
    }

    private static EvalInput judgeInput(RubricItem... items) {
        return new EvalInput(new EvalSample(null, "daily", "qq", "bot01", "g1",
                1L, "trace-1", "输入摘要", "输出摘要"),
                List.of(items), "轨迹视图", new SampleFacts(1, 10L, true));
    }

    private static RubricItem judge(String dimension, boolean redLine) {
        return new RubricItem(dimension, "检查项-" + dimension, RubricItem.KIND_JUDGE, redLine, null, null);
    }

    @Test
    void 剧本正常解析_分数判定与judgeModel齐备() {
        when(modelGateway.callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(LlmCallMeta.class),
                anyString(), any(), any())).thenReturn(CallOnceResult.success("""
                {"items":[{"index":0,"passed":true,"evidence":"切题"},{"index":1,"passed":false,"evidence":"越权"}],
                 "explanation":"回应切题但安全项失败，证据见上"}
                """, null));

        EvalOutcome outcome = evaluator.evaluate(judgeInput(
                judge("respond", false), judge("safety", true)));

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.judgeModel()).isEqualTo("judge-model-x");
        assertThat(outcome.decision()).isEqualTo(EvalResultDO.DECISION_BADCASE);   // 红线失败
        assertThat(outcome.score().total()).isNull();
        assertThat(outcome.score().safety()).isZero();
        assertThat(outcome.explanation()).contains("安全项失败");
    }

    @Test
    void 调用参数_低温度加强制JSON() {
        when(modelGateway.callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(LlmCallMeta.class),
                anyString(), any(), any())).thenReturn(CallOnceResult.success(
                "{\"items\":[],\"explanation\":\"x\"}", null));

        evaluator.evaluate(judgeInput(judge("respond", false)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<GenerationParams> params = ArgumentCaptor.forClass(GenerationParams.class);
        verify(modelGateway).callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(LlmCallMeta.class),
                anyString(), any(List.class), params.capture());
        assertThat(params.getValue().temperature()).isEqualTo(0.1);
        assertThat(params.getValue().structuredOutput()).isTrue();
    }

    @Test
    void 漏判检查项_按失败保守处理() {
        when(modelGateway.callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(LlmCallMeta.class),
                anyString(), any(), any())).thenReturn(CallOnceResult.success(
                "{\"items\":[{\"index\":0,\"passed\":true}],\"explanation\":\"漏了第二项\"}", null));

        EvalOutcome outcome = evaluator.evaluate(judgeInput(
                judge("respond", false), judge("process", false)));

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.itemVerdicts()).hasSize(2);
        assertThat(outcome.itemVerdicts().get(1).passed()).isFalse();
        assertThat(outcome.itemVerdicts().get(1).evidence()).contains("未给出");
    }

    @Test
    void 非法JSON_评估失败() {
        when(modelGateway.callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(LlmCallMeta.class),
                anyString(), any(), any())).thenReturn(CallOnceResult.success("不是JSON", null));

        assertThat(evaluator.evaluate(judgeInput(judge("respond", false))).failed()).isTrue();
    }

    @Test
    void explanation缺失_评估失败() {
        when(modelGateway.callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(LlmCallMeta.class),
                anyString(), any(), any())).thenReturn(CallOnceResult.success(
                "{\"items\":[{\"index\":0,\"passed\":true}]}", null));

        EvalOutcome outcome = evaluator.evaluate(judgeInput(judge("respond", false)));

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.failureReason()).contains("explanation");
    }

    @Test
    void 调用失败_评估失败带错误码() {
        when(modelGateway.callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(LlmCallMeta.class),
                anyString(), any(), any())).thenReturn(
                CallOnceResult.failure("llm.config_missing", "槽位未绑定"));

        assertThat(evaluator.evaluate(judgeInput(judge("respond", false))).failureReason())
                .contains("llm.config_missing");
    }

    @Test
    void 纯code题目_无judge项透传空outcome不调模型() {
        EvalOutcome outcome = evaluator.evaluate(judgeInput());

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.itemVerdicts()).isEmpty();
        verify(modelGateway, org.mockito.Mockito.never()).callOnce(anyString(), any(), anyString(),
                any(), any());
    }
}
