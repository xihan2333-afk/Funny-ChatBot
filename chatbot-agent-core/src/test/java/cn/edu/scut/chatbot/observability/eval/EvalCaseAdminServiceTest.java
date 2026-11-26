// 守护纪律：评测集管理面（FR-028）——入库结构校验门口拒绝、Rubric 修订版本递增
// 留痕（spec US5 验收场景 4）、draft 初稿不进可回测状态、废弃软删
package cn.edu.scut.chatbot.observability.eval;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.llm.gateway.CallOnceResult;
import cn.edu.scut.chatbot.llm.gateway.ModelGateway;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.SlotResolver;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.ModelConfig;
import cn.edu.scut.chatbot.loop.ModelRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvalCaseAdminServiceTest {

    private EvalCaseMapper caseMapper;
    private EvalResultMapper resultMapper;
    private ModelGateway modelGateway;
    private EvalCaseAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        caseMapper = mock(EvalCaseMapper.class);
        resultMapper = mock(EvalResultMapper.class);
        SlotResolver slotResolver = mock(SlotResolver.class);
        modelGateway = mock(ModelGateway.class);
        when(slotResolver.resolve(ModelCatalog.SLOT_EVAL_JUDGE)).thenReturn(new ModelConfig(
                new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "p", "judge-model-x",
                        "http://x", null, null, ModelCatalog.SLOT_EVAL_JUDGE),
                new GenerationParams(0.2, 2048, true, Map.of())));
        service = new EvalCaseAdminServiceImpl(caseMapper, resultMapper,
                mock(TrajectoryAssembler.class),
                new AgentJudgeEvaluator(modelGateway, slotResolver, new RubricJudge(), Clock.systemUTC()),
                modelGateway, Clock.systemUTC());
    }

    private static EvalCaseDO stored(long id, int rubricVersion, String status) {
        EvalCaseDO row = new EvalCaseDO();
        row.setId(id);
        row.setCaseType(EvalCaseDO.TYPE_SEED);
        row.setRubricVersion(rubricVersion);
        row.setStatus(status);
        return row;
    }

    private static String validScenario() {
        return new ScenarioCard("群背景", List.of(),
                new ScenarioCard.PreloadedState(null, null), List.of(),
                new ScenarioCard.TriggerEvent("message", "触发")).toJson();
    }

    private static String validRubric() {
        return RubricItem.writeRubric(List.of(
                new RubricItem("respond", "切题", RubricItem.KIND_JUDGE, false, null, null)));
    }

    @Test
    void 人工录入_结构合法入库ready_坏结构门口拒绝() {
        // 模拟 MyBatis useGeneratedKeys：insert 后回填自增主键（否则 create 里
        // selectById(row.getId()) 拿到 null 拆箱即 NPE，与真实数据库行为不符）
        when(caseMapper.insert(any(EvalCaseDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, EvalCaseDO.class).setId(77L);
            return 1;
        });
        when(caseMapper.selectById(anyLong())).thenAnswer(inv ->
                stored(inv.getArgument(0, Long.class), 1, EvalCaseDO.STATUS_READY));

        EvalCaseView view = service.create(new CreateCaseCommand(
                EvalCaseDO.TYPE_SEED, validScenario(), validRubric(),
                null, null, null, null, null, null, null));

        assertThat(view.status()).isEqualTo(EvalCaseDO.STATUS_READY);
        assertThat(view.rubricVersion()).isEqualTo(1);

        assertThatThrownBy(() -> service.create(new CreateCaseCommand(
                EvalCaseDO.TYPE_SEED, "{\"bad\":1}", validRubric(), null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CreateCaseCommand(
                EvalCaseDO.TYPE_SEED, validScenario(), "[]", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rubric修订_版本递增留痕_已废弃拒绝() {
        when(caseMapper.selectById(7L)).thenReturn(stored(7L, 2, EvalCaseDO.STATUS_READY));

        service.reviseRubric(7L, validRubric());

        verify(caseMapper).updateRubric(7L, validRubric(), 3);

        when(caseMapper.selectById(8L)).thenReturn(stored(8L, 1, EvalCaseDO.STATUS_DISCARDED));
        assertThatThrownBy(() -> service.reviseRubric(8L, validRubric()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("废弃");
    }

    @Test
    void 废弃_软删_重复废弃幂等() {
        when(caseMapper.selectById(7L)).thenReturn(stored(7L, 1, EvalCaseDO.STATUS_DRAFT));

        service.discard(7L);
        verify(caseMapper).updateStatus(7L, EvalCaseDO.STATUS_DISCARDED);

        when(caseMapper.selectById(7L)).thenReturn(stored(7L, 1, EvalCaseDO.STATUS_DISCARDED));
        service.discard(7L);   // 已废弃直接返回不抛
    }

    @Test
    void 沉淀方向校验_错题要badcase_黄金要pass() {
        EvalResultDO badcase = new EvalResultDO();
        badcase.setId(1L);
        badcase.setDecision(EvalResultDO.DECISION_BADCASE);
        badcase.setBatchId(1L);
        when(resultMapper.selectById(1L)).thenReturn(badcase);

        assertThatThrownBy(() -> service.promoteGoldenFromResult(1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("判定方向不符");
    }

    @Test
    void 生成初稿_输出结构非法判不可重试失败() {
        // 假上游返回合法 JSON 但 scenario 缺五节点——人工不可修订的坏稿直接丢弃
        when(modelGateway.callOnce(eq(ModelCatalog.SLOT_EVAL_JUDGE), any(), anyString(),
                any(), any())).thenReturn(CallOnceResult.success(
                "{\"scenario\":{\"groupBackground\":\"\"},\"rubric\":[]}", null));

        assertThatThrownBy(() -> service.generateDraft(new GenerateHint(
                GenerateHint.NATURE_COOPERATE, GenerateHint.DOMAIN_PERSONA, null)))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void 生成初稿_提示缺双轴拒绝_槽位未绑定先炸明确异常() {
        assertThatThrownBy(() -> service.generateDraft(new GenerateHint(null, "人格表达", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("互动性质");
    }
}
