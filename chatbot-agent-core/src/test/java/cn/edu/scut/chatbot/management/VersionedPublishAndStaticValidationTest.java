package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.engine.intake.HttpToolConfigDO;
import cn.edu.scut.chatbot.engine.intake.HttpToolConfigMapper;
import cn.edu.scut.chatbot.engine.intake.ToolConfigMapper;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.observability.eval.EvalCaseDO;
import cn.edu.scut.chatbot.observability.eval.EvalCaseMapper;
import cn.edu.scut.chatbot.observability.flywheel.PublishGateService;
import cn.edu.scut.chatbot.observability.flywheel.PublishGateServiceImpl;
import cn.edu.scut.chatbot.observability.replay.ReplayReport;
import cn.edu.scut.chatbot.observability.replay.ReplayService;
import cn.edu.scut.chatbot.prompt.ContentVersioningService;
import cn.edu.scut.chatbot.prompt.PromptAdminService;
import cn.edu.scut.chatbot.prompt.PromptFragmentDO;
import cn.edu.scut.chatbot.prompt.PromptFragmentMapper;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import cn.edu.scut.chatbot.skill.SkillAdminService;
import cn.edu.scut.chatbot.skill.SkillMapper;
import cn.edu.scut.chatbot.skill.SkillService;
import cn.edu.scut.chatbot.tool.generic.HttpGenericTool;
import cn.edu.scut.chatbot.tool.registry.ToolAdminService;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VersionedPublishAndStaticValidationTest {

    @Test
    void promptAdminBlocksLockedFragmentAndUndeclaredVariables() {
        PromptFragmentMapper fragmentMapper = mock(PromptFragmentMapper.class);
        SkillMapper skillMapper = mock(SkillMapper.class);
        ContentVersioningService versioning = new ContentVersioningService(fragmentMapper, skillMapper, null);
        PublishGateService gateService = mock(PublishGateService.class);
        PromptAdminService service = new PromptAdminService(fragmentMapper, versioning, gateService);

        PromptFragmentDO locked = new PromptFragmentDO();
        locked.setFragmentKey("builtin.skeleton");
        locked.setBuiltinLocked(1);
        when(fragmentMapper.selectByKey("builtin.skeleton")).thenReturn(locked);

        assertThatThrownBy(() -> service.saveDraft("builtin.skeleton",
                new PromptAdminService.FragmentUpsertRequest("builtin.skeleton", "protocol", "all", "t", "hi", List.of())))
                .isInstanceOf(ChatBotException.class)
                .extracting("errorCode")
                .isEqualTo("mgmt.fragment_locked");

        when(fragmentMapper.selectByKey("custom.greet")).thenReturn(null);
        assertThatThrownBy(() -> service.saveDraft("custom.greet",
                new PromptAdminService.FragmentUpsertRequest(
                        "custom.greet", "standalone", "all", "Greet", "你好 {{undeclaredVar}}", List.of("userName"))))
                .isInstanceOf(ChatBotException.class)
                .extracting("errorCode")
                .isEqualTo("mgmt.undeclared_variable");
    }

    @Test
    void skillAdminRejectsDescriptionOver200Chars() {
        SkillMapper skillMapper = mock(SkillMapper.class);
        SkillService skillService = mock(SkillService.class);
        ContentVersioningService versioning = mock(ContentVersioningService.class);
        PublishGateService gate = mock(PublishGateService.class);
        SkillAdminService service = new SkillAdminService(
                skillMapper, skillService, versioning, gate,
                mock(cn.edu.scut.chatbot.engine.intake.GroupMapper.class),
                mock(cn.edu.scut.chatbot.engine.intake.GroupConfigMapper.class));

        String over200 = "x".repeat(201);
        assertThatThrownBy(() -> service.saveDraft("too-long",
                new SkillAdminService.SkillUpsertRequest("too-long", over200, "body", true), "admin"))
                .isInstanceOf(ChatBotException.class)
                .extracting("errorCode")
                .isEqualTo("mgmt.skill_description_too_long");
    }

    @Test
    void httpToolWarnsPrivateNetworkAndToolRegistryIgnoresDrafts() {
        HttpToolConfigMapper httpMapper = mock(HttpToolConfigMapper.class);
        ToolConfigMapper toolConfigMapper = mock(ToolConfigMapper.class);
        ContentVersioningService versioning = mock(ContentVersioningService.class);
        HttpToolConfigDO saved = new HttpToolConfigDO();
        saved.setId(10L);
        saved.setStatus("draft");
        saved.setVersionNo(1);
        when(versioning.saveHttpToolConfigDraft(any())).thenReturn(saved);

        ToolAdminService toolAdmin = new ToolAdminService(
                List.of(), toolConfigMapper, httpMapper, mock(CredentialMapper.class),
                versioning, mock(PublishGateService.class));

        var res = toolAdmin.saveHttpToolDraft("localWeather", new ToolAdminFacades.HttpToolUpsertRequest(
                "localWeather", "天气", "{\"type\":\"object\"}", "GET",
                "http://127.0.0.1:8088/weather", null, null, null, null, 4096, 3000, "FAST", true));
        assertThat(res.warnings()).anyMatch(w -> w.contains("私有网段"));

        // ToolRegistry 草稿隔离
        HttpToolConfigDO draftTool = new HttpToolConfigDO();
        draftTool.setToolName("draftOnlyTool");
        draftTool.setEnabled(1);
        draftTool.setStatus("draft");
        when(httpMapper.selectAll()).thenReturn(List.of(draftTool));

        HttpGenericTool genericFactory = mock(HttpGenericTool.class);
        ToolRegistry registry = new ToolRegistry(List.of(), toolConfigMapper, httpMapper, genericFactory);
        assertThat(registry.snapshot()).doesNotContainKey("draftOnlyTool");
        verifyNoInteractions(genericFactory);
    }

    @Test
    void publishGateRoutesFiveEntityKindsAndHandlesRedYellowGreen() throws Exception {
        EvalCaseMapper caseMapper = mock(EvalCaseMapper.class);
        ReplayService replayService = mock(ReplayService.class);
        ContentVersioningService versioning = mock(ContentVersioningService.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);

        EvalCaseDO redCase = new EvalCaseDO();
        redCase.setId(1L);
        redCase.setRubric("[{\"description\":\"safety\",\"dimension\":\"safety\",\"kind\":\"judge\",\"redLine\":true}]");
        EvalCaseDO expCase = new EvalCaseDO();
        expCase.setId(2L);
        expCase.setRubric("[{\"description\":\"tone\",\"dimension\":\"respond\",\"kind\":\"judge\",\"redLine\":false}]");
        when(caseMapper.selectReadyByTypes(any(), anyInt())).thenReturn(List.of(redCase, expCase));

        when(replayService.run(eq(redCase), eq(1))).thenReturn(
                new ReplayReport(1L, 1L, 1, 1, 1.0, "v1",
                        List.of(new ReplayReport.ExecutionDetail(1, "pass", List.of(), List.of(), null)),
                        "{}", null));
        when(replayService.run(eq(expCase), eq(1))).thenReturn(
                new ReplayReport(2L, 2L, 1, 0, 0.5, "v1",
                        List.of(new ReplayReport.ExecutionDetail(1, "fail", List.of(), List.of(), "tone")),
                        "{}", null));

        PublishGateServiceImpl gate = new PublishGateServiceImpl(caseMapper, replayService, versioning, recorder);
        gate.trigger("skill:my-skill");

        PublishGateService.GateStatus status = null;
        for (int i = 0; i < 30; i++) {
            status = gate.status("skill:my-skill");
            if (!"running".equals(status.status())) break;
            Thread.sleep(20);
        }
        assertThat(status).isNotNull();
        assertThat(status.status()).isEqualTo("yellow");
        assertThat(status.redLinePassed()).isTrue();
        assertThat(status.experienceWarnings()).isNotEmpty();

        gate.publish("skill:my-skill", null);
        verify(versioning).publishSkill("my-skill");
    }
}
