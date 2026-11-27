package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagementAuditInterceptorTest {

    static class DummyController {
        @GetMapping("/read")
        public void read() {
        }

        @PostMapping("/write")
        @AuditAction(target = "group_config", action = "update", summary = "更新群配置")
        public void write() {
        }

        @PostMapping("/skip")
        @SkipAutoAudit
        public void skip() {
        }
    }

    @Test
    void writeRequestRecordsAdminOperateAndSanitizesSensitiveKeys() throws Exception {
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        ManagementAuditInterceptor interceptor = new ManagementAuditInterceptor(recorder);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/management/groups/1/config");
        request.setAttribute(ManagementAuthFilter.ATTR_OPERATOR, "admin");
        request.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "groupId", "20001",
                "password", "plain-pass",
                "nested", Map.of("apiKey", "sk-123456", "safe", "ok")
        ));
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        Method method = DummyController.class.getMethod("write");
        HandlerMethod hm = new HandlerMethod(new DummyController(), method);

        interceptor.afterCompletion(request, response, hm, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(recorder).record(
                eq(SecurityEventKind.ADMIN_OPERATE),
                eq(SecurityDims.none()),
                detailCaptor.capture(),
                eq("4bf92f3577b34da6a3ce929d0e0e4736")
        );

        Map<String, Object> recorded = detailCaptor.getValue();
        assertThat(recorded.get("operator")).isEqualTo("admin");
        assertThat(recorded.get("target")).isEqualTo("group_config");
        assertThat(recorded.get("action")).isEqualTo("update");
        assertThat(recorded.get("summary")).isEqualTo("更新群配置");
        assertThat(recorded.get("success")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) recorded.get("extra");
        assertThat(extra.get("groupId")).isEqualTo("20001");
        assertThat(extra.get("password")).isEqualTo("***");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) extra.get("nested");
        assertThat(nested.get("apiKey")).isEqualTo("***");
        assertThat(nested.get("safe")).isEqualTo("ok");
    }

    @Test
    void skipAutoAuditAnnotationPreventsDoubleRecording() throws Exception {
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        ManagementAuditInterceptor interceptor = new ManagementAuditInterceptor(recorder);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/management/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod hm = new HandlerMethod(new DummyController(), DummyController.class.getMethod("skip"));

        interceptor.afterCompletion(request, response, hm, null);

        verifyNoInteractions(recorder);
    }

    @Test
    void readRequestIsNeverAudited() throws Exception {
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        ManagementAuditInterceptor interceptor = new ManagementAuditInterceptor(recorder);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/management/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod hm = new HandlerMethod(new DummyController(), DummyController.class.getMethod("read"));

        interceptor.afterCompletion(request, response, hm, null);

        verifyNoInteractions(recorder);
    }

    @Test
    void recorderExceptionDoesNotPropagate() throws Exception {
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        doThrow(new RuntimeException("db down")).when(recorder).record(any(), any(), any(), any());
        ManagementAuditInterceptor interceptor = new ManagementAuditInterceptor(recorder);

        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/management/groups/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod hm = new HandlerMethod(new DummyController(), DummyController.class.getMethod("write"));

        interceptor.afterCompletion(request, response, hm, null);
        verify(recorder).record(eq(SecurityEventKind.ADMIN_OPERATE), any(), any(), any());
    }
}
