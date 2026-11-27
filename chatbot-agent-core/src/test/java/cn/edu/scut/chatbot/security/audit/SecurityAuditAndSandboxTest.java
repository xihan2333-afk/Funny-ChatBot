// 守护纪律：八类安全事件字段完整性、Span 安全标记三属性挂载、沙箱特征扫描零误报边界与异常模式阈值（security-gate 契约第 7、8 节）
package cn.edu.scut.chatbot.security.audit;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecurityAuditAndSandboxTest {

    private SystemParamService systemParams;
    private SecurityEventRecorder recorder;
    private OutputFeatureScanner scanner;
    private SandboxAuditServiceImpl sandboxAudit;

    @BeforeEach
    void setUp() {
        systemParams = mock(SystemParamService.class);
        recorder = mock(SecurityEventRecorder.class);
        when(systemParams.getString("security.sandbox.featurePatterns", null)).thenReturn(null);
        when(systemParams.getInt("security.sandbox.highFreqPer5Min", 10)).thenReturn(3);
        scanner = new OutputFeatureScanner(systemParams);
        sandboxAudit = new SandboxAuditServiceImpl(
                scanner, recorder, systemParams,
                Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void 八类安全事件枚举完整且SecurityEventRecorder写入全部字段() {
        assertEquals(8, SecurityEventKind.values().length);
        SecurityEventMapper mapper = mock(SecurityEventMapper.class);
        SecurityEventRecorderImpl realRecorder = new SecurityEventRecorderImpl(
                mapper, Clock.fixed(Instant.parse("2026-11-26T08:00:00Z"), ZoneId.of("Asia/Shanghai")));

        for (SecurityEventKind kind : SecurityEventKind.values()) {
            realRecorder.record(kind, SecurityDims.ofUser("qq", "b1", "g1", "u1"),
                    Map.of("kindCheck", kind.code()), "trace-" + kind.code());
        }
        verify(mapper, times(8)).insert(argThat(row ->
                row.getEventKind() != null
                        && "qq".equals(row.getPlatform())
                        && "g1".equals(row.getGroupId())
                        && "u1".equals(row.getUserId())
                        && row.getDetail() != null && row.getDetail().contains("kindCheck")
                        && row.getTraceId() != null && row.getTraceId().startsWith("trace-")));
    }

    @Test
    void Span安全标记三属性完整挂载到工具Span() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter inMemory = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                exported.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(inMemory))
                .build();
        SpanSecurityMarker marker = new SpanSecurityMarker(() -> provider.get("test-security"));

        marker.emitInterceptedToolSpan("runCode", "runCodeQuota", "beforeToolCall", "qq", "b1", "g1");

        assertEquals(1, exported.size());
        SpanData span = exported.get(0);
        assertEquals("tool.runCode", span.getName());
        assertEquals(Boolean.TRUE, span.getAttributes().get(AttributeKey.booleanKey(SpanSecurityMarker.ATTR_INTERCEPTED)));
        assertEquals("runCodeQuota", span.getAttributes().get(AttributeKey.stringKey(SpanSecurityMarker.ATTR_RULE)));
        assertEquals("beforeToolCall", span.getAttributes().get(AttributeKey.stringKey(SpanSecurityMarker.ATTR_CHANNEL)));
        provider.close();
    }

    @Test
    void 沙箱正常代码输出零误报_预埋dockerSock精准命中记事件() {
        SandboxAuditService.AuditResult clean = sandboxAudit.audit(
                new SandboxAuditService.SandboxExecutionView(0, false, "sum = 5050\nhello world", "", 42, null),
                SecurityDims.ofGroup("qq", "b1", "g-clean"), "trace-clean");
        assertFalse(clean.abnormal());
        assertTrue(clean.matchedFeatures().isEmpty());
        verify(recorder, never()).record(any(), any(), any(), any());

        SandboxAuditService.AuditResult hit = sandboxAudit.audit(
                new SandboxAuditService.SandboxExecutionView(0, false, "ls /var/run/docker.sock", "", 55, null),
                SecurityDims.ofGroup("qq", "b1", "g-hit"), "trace-hit");
        assertTrue(hit.abnormal());
        assertFalse(hit.matchedFeatures().isEmpty());
        verify(recorder).record(eq(SecurityEventKind.SANDBOX_ABNORMAL),
                eq(SecurityDims.ofGroup("qq", "b1", "g-hit")),
                argThat(detail -> detail.get("matchedFeatures") != null),
                eq("trace-hit"));
    }

    @Test
    void 沙箱退出码异常超时及同群高频执行触发异常审计() {
        SandboxAuditService.AuditResult oom = sandboxAudit.audit(
                new SandboxAuditService.SandboxExecutionView(137, false, "", "Killed", 900, "oom"),
                SecurityDims.ofGroup("qq", "b1", "g-oom"), "trace-oom");
        assertTrue(oom.abnormal());
        assertTrue(oom.anomalyReasons().stream().anyMatch(r -> r.startsWith("abnormalExitCode:")));

        // 同群高频阈值设为 3：前 2 次正常，第 3 次触发 highFrequency
        SecurityDims freqDims = SecurityDims.ofGroup("qq", "b1", "g-freq");
        assertFalse(sandboxAudit.audit(
                new SandboxAuditService.SandboxExecutionView(0, false, "ok1", "", 10, null), freqDims, null).abnormal());
        assertFalse(sandboxAudit.audit(
                new SandboxAuditService.SandboxExecutionView(0, false, "ok2", "", 10, null), freqDims, null).abnormal());
        SandboxAuditService.AuditResult third = sandboxAudit.audit(
                new SandboxAuditService.SandboxExecutionView(0, false, "ok3", "", 10, null), freqDims, null);
        assertTrue(third.abnormal());
        assertTrue(third.anomalyReasons().stream().anyMatch(r -> r.startsWith("highFrequency:")));
    }
}
