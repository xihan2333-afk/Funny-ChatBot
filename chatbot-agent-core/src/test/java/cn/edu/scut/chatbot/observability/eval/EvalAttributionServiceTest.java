// 守护纪律：归因三分支流转（字典校验 / 留痕不改判）与人机一致率计算
// （五组指标分别统计、带宽内一致、维度缺分不计入——FR-010，T047）
package cn.edu.scut.chatbot.observability.eval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EvalAttributionServiceTest {

    private EvalResultMapper resultMapper;
    private EvalAttributionService service;

    @BeforeEach
    void setUp() {
        resultMapper = mock(EvalResultMapper.class);
        service = new EvalAttributionService(resultMapper);
    }

    private static EvalResultDO row(long id, long batchId, Long turnId, Long caseId,
                                    String judgeModel, EvalScore score, String decision) {
        EvalResultDO row = new EvalResultDO();
        row.setId(id);
        row.setBatchId(batchId);
        row.setTurnId(turnId);
        row.setCaseId(caseId);
        row.setSourceKind(EvalResultDO.SOURCE_BLIND);
        row.setScore(score.toJson());
        row.setDecision(decision);
        row.setJudgeModel(judgeModel);
        row.setCreatedAt(LocalDateTime.of(2026, 11, 26, 10, 0));
        return row;
    }

    // ===== 归因三分支 =====

    @Test
    void 三分支字典合法_逐分支回填成功() {
        when(resultMapper.selectById(1)).thenReturn(row(1, 1, 10L, null, "judge-model",
                new EvalScore(40, null, null, null, null, null), "badcase"));
        when(resultMapper.selectById(2)).thenReturn(row(2, 1, 11L, null, "judge-model",
                new EvalScore(40, null, null, null, null, null), "badcase"));
        when(resultMapper.selectById(3)).thenReturn(row(3, 1, 12L, null, "judge-model",
                new EvalScore(40, null, null, null, null, null), "badcase"));

        service.attribute(1, EvalResultDO.ATTRIBUTION_AGENT);
        service.attribute(2, EvalResultDO.ATTRIBUTION_CASE_MISS);
        service.attribute(3, EvalResultDO.ATTRIBUTION_RUBRIC_ERROR);

        verify(resultMapper).updateAttribution(1, "agent");
        verify(resultMapper).updateAttribution(2, "caseMiss");
        verify(resultMapper).updateAttribution(3, "rubricError");
    }

    @Test
    void 归因字典外取值_非法入参拒绝() {
        assertThatThrownBy(() -> service.attribute(1, "modelError"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent / caseMiss / rubricError");
    }

    @Test
    void 结果不存在或已归因_拒绝改判() {
        when(resultMapper.selectById(99)).thenReturn(null);
        assertThatThrownBy(() -> service.attribute(99, "agent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不存在");

        EvalResultDO attributed = row(1, 1, 10L, null, "m", new EvalScore(40, null, null, null, null, null), "badcase");
        attributed.setAttribution("caseMiss");
        when(resultMapper.selectById(1)).thenReturn(attributed);
        assertThatThrownBy(() -> service.attribute(1, "agent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("留痕");
    }

    // ===== 人机一致率 =====

    @Test
    void 一致率_五组分别统计_带宽内一致_维度缺分不计入() {
        EvalResultDO machine = row(1, 100, 10L, null, "judge-model",
                new EvalScore(null, 80, 60, null, 90, 40), "badcase");
        EvalResultDO human = row(2, 100, 10L, null, null,
                new EvalScore(null, 85, 95, null, 88, 42), "badcase");
        when(resultMapper.selectBlindBetween(any(), any())).thenReturn(List.of(machine, human));

        EvalAttributionService.AgreementReport report = service.agreement(
                LocalDateTime.of(2026, 11, 25, 0, 0), LocalDateTime.of(2026, 11, 27, 0, 0));

        assertThat(report.pairs()).isEqualTo(1);
        assertThat(report.decisionAgreed()).isEqualTo(1);   // 双方都 badcase
        for (EvalAttributionService.DimensionAgreement dimension : report.dimensions()) {
            switch (dimension.dimension()) {
                case "respond" -> {   // |80-85|=5 ≤ 10 一致
                    assertThat(dimension.count()).isEqualTo(1);
                    assertThat(dimension.agreementRate()).isEqualTo(1.0);
                }
                case "process" -> {   // |60-95|=35 > 10 不一致
                    assertThat(dimension.count()).isEqualTo(1);
                    assertThat(dimension.agreementRate()).isEqualTo(0.0);
                }
                case "form" -> assertThat(dimension.count()).isZero();       // 双方缺分
                case "safety" -> assertThat(dimension.count()).isEqualTo(1); // |40-42|=2 一致
                default -> {
                }
            }
        }
    }

    @Test
    void 一致率_配对键区分turn与case_非盲评行不进对() {
        EvalResultDO machineTurn = row(1, 100, 10L, null, "judge-model",
                new EvalScore(null, 80, null, null, null, null), "pass");
        EvalResultDO humanTurn = row(2, 100, 10L, null, null,
                new EvalScore(null, 80, null, null, null, null), "pass");
        // 同批不同 turn 的机器行——不得与上面的人工行配对
        EvalResultDO machineOtherTurn = row(3, 100, 11L, null, "judge-model",
                new EvalScore(null, 10, null, null, null, null), "badcase");
        // case 样本对（replay 批次盲评行）
        EvalResultDO machineCase = row(4, 200, null, 5L, "judge-model",
                new EvalScore(null, 70, null, null, null, null), "pass");
        EvalResultDO humanCase = row(5, 200, null, 5L, null,
                new EvalScore(null, 72, null, null, null, null), "pass");
        when(resultMapper.selectBlindBetween(any(), any()))
                .thenReturn(List.of(machineTurn, humanTurn, machineOtherTurn, machineCase, humanCase));

        EvalAttributionService.AgreementReport report = service.agreement(
                LocalDateTime.of(2026, 11, 25, 0, 0), LocalDateTime.of(2026, 11, 27, 0, 0));

        assertThat(report.pairs()).isEqualTo(2);   // turn 对 + case 对；孤立机器行不进对
    }
}
