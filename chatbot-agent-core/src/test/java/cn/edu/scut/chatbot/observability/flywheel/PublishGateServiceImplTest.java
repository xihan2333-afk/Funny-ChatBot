// 守护纪律：门禁红黄绿判定与空集提示、红牌越过必填理由并留审计（T061 单元面——
// eval-and-flywheel 契约第 6 节）
package cn.edu.scut.chatbot.observability.flywheel;

import cn.edu.scut.chatbot.observability.eval.EvalCaseDO;
import cn.edu.scut.chatbot.observability.eval.EvalCaseMapper;
import cn.edu.scut.chatbot.observability.eval.RubricItem;
import cn.edu.scut.chatbot.observability.replay.ReplayReport;
import cn.edu.scut.chatbot.observability.replay.ReplayService;
import cn.edu.scut.chatbot.prompt.ContentVersioningService;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PublishGateServiceImplTest {

    private EvalCaseMapper caseMapper;
    private ReplayService replayService;
    private ContentVersioningService versioningService;
    private SecurityEventRecorder eventRecorder;
    private PublishGateServiceImpl gate;

    @BeforeEach
    void setUp() {
        caseMapper = mock(EvalCaseMapper.class);
        replayService = mock(ReplayService.class);
        versioningService = mock(ContentVersioningService.class);
        eventRecorder = mock(SecurityEventRecorder.class);
        gate = new PublishGateServiceImpl(caseMapper, replayService, versioningService, eventRecorder);
    }

    /**
     * 红线题（redLine 检查项）
     */
    private static EvalCaseDO redLineCase(long id) {
        EvalCaseDO caze = new EvalCaseDO();
        caze.setId(id);
        caze.setRubric(RubricItem.writeRubric(List.of(
                new RubricItem("safety", "红线", RubricItem.KIND_JUDGE, true, null, null))));
        return caze;
    }

    private static ReplayReport report(long caseId, String... decisions) {
        List<ReplayReport.ExecutionDetail> details = new java.util.ArrayList<>();
        int passed = 0;
        for (int i = 0; i < decisions.length; i++) {
            details.add(new ReplayReport.ExecutionDetail(i + 1, decisions[i], List.of(), List.of(), null));
            if ("pass".equals(decisions[i])) {
                passed++;
            }
        }
        return new ReplayReport(1, caseId, decisions.length, passed,
                decisions.length == 0 ? 0.0 : (double) passed / decisions.length,
                "1/0/0", details, "{}", null);
    }

    @Test
    void 红线题任一次不过_红牌() {
        when(caseMapper.selectReadyByTypes(any(), eq(200))).thenReturn(List.of(redLineCase(1)));
        when(replayService.run(any(), any())).thenReturn(report(1, "badcase"));

        gate.trigger("fragment.a");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                !"running".equals(gate.status("fragment.a").status()));
        assertThat(gate.status("fragment.a").status()).isEqualTo("red");
        assertThat(gate.status("fragment.a").passedCases()).isZero();
    }

    @Test
    void 全过_绿牌() {
        when(caseMapper.selectReadyByTypes(any(), eq(200))).thenReturn(List.of(redLineCase(1)));
        when(replayService.run(any(), any())).thenReturn(report(1, "pass"));

        gate.trigger("fragment.b");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                "green".equals(gate.status("fragment.b").status()));
        assertThat(gate.status("fragment.b").emptySubset()).isFalse();
        assertThat(gate.status("fragment.b").passedCases()).isEqualTo(1);
    }

    @Test
    void 必过子集为空_绿牌附空集提示() {
        when(caseMapper.selectReadyByTypes(any(), eq(200))).thenReturn(List.of());

        gate.trigger("fragment.c");

        await().atMost(Duration.ofSeconds(5)).until(() ->
                "green".equals(gate.status("fragment.c").status()));
        assertThat(gate.status("fragment.c").emptySubset()).isTrue();   // 防空集假绿
    }

    @Test
    void 红牌发布_无理由拒绝_有理由审计后发布() {
        when(caseMapper.selectReadyByTypes(any(), eq(200))).thenReturn(List.of(redLineCase(1)));
        when(replayService.run(any(), any())).thenReturn(report(1, "badcase"));
        gate.trigger("fragment.d");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                "red".equals(gate.status("fragment.d").status()));

        assertThatThrownBy(() -> gate.publish("fragment.d", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overrideReason");
        verify(versioningService, never()).publishFragment(any());

        gate.publish("fragment.d", "线上事故修复优先，风险自担");
        verify(eventRecorder).record(eq(SecurityEventKind.ADMIN_OPERATE), any(SecurityDims.class),
                org.mockito.ArgumentMatchers.argThat(detail -> String.valueOf(detail.get("overrideReason"))
                        .contains("线上事故修复优先")), eq(null));
        verify(versioningService).publishFragment("fragment.d");
    }

    @Test
    void 绿牌发布_无需理由_不产生审计() {
        when(caseMapper.selectReadyByTypes(any(), eq(200))).thenReturn(List.of());
        gate.trigger("fragment.e");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                "green".equals(gate.status("fragment.e").status()));

        gate.publish("fragment.e", null);

        verify(versioningService).publishFragment("fragment.e");
        verify(eventRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    void 未trigger的意图_状态查询拒绝() {
        assertThatThrownBy(() -> gate.status("fragment.unknown"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trigger");
    }

    @Test
    void 安全面意图识别覆盖能力协议与安全闸与沙箱与safety四类前缀() {
        assertThat(PublishGateServiceImpl.isSecuritySurface("tool_protocol.system")).isTrue();
        assertThat(PublishGateServiceImpl.isSecuritySurface("SECURITY.gatepolicy")).isTrue();
        assertThat(PublishGateServiceImpl.isSecuritySurface("sandbox.exec_policy")).isTrue();
        assertThat(PublishGateServiceImpl.isSecuritySurface("prompt.safety_rules")).isTrue();

        assertThat(PublishGateServiceImpl.isSecuritySurface("persona.daily_chat")).isFalse();
        assertThat(PublishGateServiceImpl.isSecuritySurface("")).isFalse();
        assertThat(PublishGateServiceImpl.isSecuritySurface(null)).isFalse();
    }

    @Test
    void 安全面意图发布自动附对抗回归且击穿时即使必过子集为空仍判红牌() {
        cn.edu.scut.chatbot.observability.eval.AttackBatchService attackBatch =
                mock(cn.edu.scut.chatbot.observability.eval.AttackBatchService.class);
        gate.setAttackBatchService(attackBatch);
        when(caseMapper.selectReadyByTypes(any(), eq(200))).thenReturn(List.of());

        // 普通意图：不触发对抗回归
        gate.trigger("persona.daily_chat");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                "green".equals(gate.status("persona.daily_chat").status()));
        verify(attackBatch, never()).runAllEnabled();

        // 安全面意图 + 对抗硬防线击穿：必过子集为空也不能假绿，必须判 red
        when(attackBatch.runAllEnabled()).thenReturn(
                new cn.edu.scut.chatbot.observability.eval.AttackBatchService.AttackBatchReport(
                        7001L, false, 3, 2, 1, 0, 0.0, List.of()));
        gate.trigger("tool_protocol.capability");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                !"running".equals(gate.status("tool_protocol.capability").status()));
        assertThat(gate.status("tool_protocol.capability").status()).isEqualTo("red");
        verify(attackBatch, times(1)).runAllEnabled();

        // 安全面意图 + 对抗全部通过：必过子集为空时才为 green 附空集提示
        when(attackBatch.runAllEnabled()).thenReturn(
                new cn.edu.scut.chatbot.observability.eval.AttackBatchService.AttackBatchReport(
                        7002L, true, 3, 3, 0, 0, 0.0, List.of()));
        gate.trigger("security.delimit_header");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                !"running".equals(gate.status("security.delimit_header").status()));
        assertThat(gate.status("security.delimit_header").status()).isEqualTo("green");
        assertThat(gate.status("security.delimit_header").emptySubset()).isTrue();
    }
}
