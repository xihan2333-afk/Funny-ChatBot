package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.bootstrap.*;
import cn.edu.scut.chatbot.security.credential.CredentialRotationLogMapper;
import cn.edu.scut.chatbot.security.credential.CredentialRotationOpsService;
import cn.edu.scut.chatbot.security.credential.CredentialRotationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SecurityOpsParamControllerTest {

    @Test
    void systemParamGroupsFiveDomainsAndPublishesRefreshEvent() {
        SystemParamMapper mapper = mock(SystemParamMapper.class);
        SystemParamDO p1 = new SystemParamDO();
        p1.setParamKey("scheduler.effects.max_retries");
        p1.setParamValue("5");
        SystemParamDO p2 = new SystemParamDO();
        p2.setParamKey("observability.sampling.enabled");
        p2.setParamValue("false");
        when(mapper.selectAll()).thenReturn(List.of(p1, p2));

        SystemParamService paramService = mock(SystemParamService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SystemParamAdminService service = new SystemParamAdminService(mapper, paramService, publisher);
        SystemParamController controller = new SystemParamController(service);

        var grouped = controller.listGrouped();
        assertThat(grouped.keySet()).containsExactly("context", "memory", "emotion", "scheduler", "observability");
        assertThat(grouped.get("scheduler")).hasSize(1);

        controller.update("scheduler.effects.max_retries",
                new SystemParamController.UpdateParamRequest("8"), new MockHttpServletRequest());
        verify(mapper).upsertParam(any());
        verify(paramService).refresh();
        verify(publisher).publishEvent(any(Object.class));
    }

    @Test
    void opsCredentialRotationValidatesKeyLengthAndConfirmFlag() {
        CredentialRotationService rotService = mock(CredentialRotationService.class);
        when(rotService.rotate(any())).thenReturn(new CredentialRotationService.RotationReport(5, 5, 0, 0));
        CredentialRotationLogMapper logMapper = mock(CredentialRotationLogMapper.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-27T05:00:00Z"), ZoneId.of("Asia/Shanghai"));
        CredentialRotationOpsService opsService = new CredentialRotationOpsService(
                rotService, logMapper, new IdGenerator(1, fixedClock), fixedClock);

        OpsAdminController controller = new OpsAdminController(
                opsService, mock(cn.edu.scut.chatbot.llm.slot.LlmAdminService.class));

        assertThatThrownBy(() -> controller.rotateCredential(
                new OpsAdminController.RotationRequest("short", false, false), "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirm=true");

        String validBase64 = Base64.getEncoder().encodeToString(new byte[32]);
        var res = controller.rotateCredential(
                new OpsAdminController.RotationRequest(validBase64, false, true), "admin");
        assertThat(res.status()).isEqualTo("completed");
        assertThat(res.rotatedCount()).isEqualTo(5);
        verify(logMapper).insert(any());
        verify(logMapper).updateFinished(any());
    }
}
