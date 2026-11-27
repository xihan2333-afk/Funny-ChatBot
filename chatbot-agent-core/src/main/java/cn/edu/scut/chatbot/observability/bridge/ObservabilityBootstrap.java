package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Collection;

/**
 * OTel 初始化（observability-bridge 契约第 1 节、research R1、T013）：双层分工的唯一
 * 代码路径——javaagent 在场（或既有 SDK 已注册——IT 测试基建）复用全局实例不重复 set；
 * 缺席则自建 SdkOpenTelemetry（BatchSpanProcessor + OtlpGrpcSpanExporter，OTLP 地址可配、
 * 可关为 noop）并注册全局。观测代码永远经全局入口取 Tracer，两层拿到同一个 SDK。
 *
 * <p>失败语义（FR-026 可丢不可卡）：初始化失败降级 noop + ERROR 日志带 errorCode
 * observe.init_failed，不阻断应用启动；导出失败由 {@link ExportHealthMetrics} 计数
 * （BatchSpanProcessor 自带退避重试与队列上限——观测允许丢、主流程不受影响）。
 */
@Component
public class ObservabilityBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityBootstrap.class);

    private final String otlpEndpoint;

    private final boolean otlpEnabled;

    private final ExportHealthMetrics exportHealth;

    private final SystemParamService systemParamService;

    /**
     * 已注册的自建 provider（应用关闭时 shutdown——BatchSpanProcessor 的批量线程随进程收尾）
     */
    private SdkTracerProvider selfBuiltProvider;

    public ObservabilityBootstrap(@Value("${observe.otlp-endpoint:}") String otlpEndpoint,
                                  @Value("${observe.otlp-enabled:true}") boolean otlpEnabled,
                                  ExportHealthMetrics exportHealth,
                                  SystemParamService systemParamService) {
        this.otlpEndpoint = otlpEndpoint == null ? "" : otlpEndpoint.trim();
        this.otlpEnabled = otlpEnabled;
        this.exportHealth = exportHealth;
        this.systemParamService = systemParamService;
    }

    @PostConstruct
    void initialize() {
        try {
            if (tracerAlreadyInstalled()) {
                // javaagent 在场（或 IT 测试基建先注册了内存 SDK）——直接用全局实例，不 set
                log.info("OTel SDK 已在场（javaagent 或测试基建），事件桥复用全局实例");
                return;
            }
            if (!otlpEnabled || otlpEndpoint.isBlank()) {
                // 观测导出关闭：保持全局 noop——事件桥拿到的 Tracer 一律无效 Span（零开销降级）
                log.info("OTLP 导出未启用（observe.otlp-enabled={}，endpoint 已{}配置），自建 SDK 保持 noop",
                        otlpEnabled, otlpEndpoint.isBlank() ? "未" : "");
                return;
            }
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .setResource(io.opentelemetry.sdk.resources.Resource.getDefault().merge(
                            io.opentelemetry.sdk.resources.Resource.create(
                                    io.opentelemetry.api.common.Attributes.of(
                                            io.opentelemetry.api.common.AttributeKey.stringKey("service.name"),
                                            "agent-core"))))
                    .addSpanProcessor(BatchSpanProcessor.builder(
                                    new HealthTrackingSpanExporter(
                                            OtlpGrpcSpanExporter.builder()
                                                    .setEndpoint(otlpEndpoint)
                                                    .build(),
                                            exportHealth))
                            .build())
                    .build();
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            GlobalOpenTelemetry.set(sdk);
            selfBuiltProvider = provider;
            log.info("事件桥自建 OTel SDK 就绪（javaagent 缺席兜底）otlp={}", otlpEndpoint);
        } catch (IllegalStateException alreadyInstalled) {
            log.info("全局 OTel 已被并发注册（javaagent premain 或测试基建），事件桥复用全局实例");
        } catch (Exception e) {
            // 初始化失败不阻断启动（观测管道缺席不拖垮业务——FR-026）
            log.error("OTel 初始化失败，观测降级 noop errorCode={} traceId=n/a",
                    ErrorCodes.OBSERVE_INIT_FAILED, e);
        }
    }

    @javax.annotation.PreDestroy
    void shutdown() {
        if (selfBuiltProvider != null) {
            selfBuiltProvider.shutdown().join(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 已有 SDK 在场判定（功能探测）：探针 Span 的 SpanContext 有效即说明全局已被注册过
     * ——javaagent premain 或测试基建先到，都不该再 set（全局唯一）。noop 下探针无效，
     * 返回 false 走自建路径。
     */
    private boolean tracerAlreadyInstalled() {
        Span probe = GlobalOpenTelemetry.get()
                .getTracer("chatbot-observability-bootstrap")
                .spanBuilder("bootstrap-probe")
                .startSpan();
        try {
            return probe.getSpanContext().isValid();
        } finally {
            probe.end();
        }
    }

    /**
     * 导出失败计数包装器：OTLP 导出的异步结果回调里记失败数（Collector 不可达信号；
     * 不参与控制流——重试与队列上限是 BatchSpanProcessor 的职责）
     */
    static final class HealthTrackingSpanExporter implements SpanExporter {

        private final SpanExporter delegate;

        private final ExportHealthMetrics health;

        HealthTrackingSpanExporter(SpanExporter delegate, ExportHealthMetrics health) {
            this.delegate = delegate;
            this.health = health;
        }

        @Override
        public CompletableResultCode export(Collection<io.opentelemetry.sdk.trace.data.SpanData> spans) {
            return track(delegate.export(spans));
        }

        private CompletableResultCode track(CompletableResultCode result) {
            result.whenComplete(() -> {
                if (!result.isSuccess()) {
                    health.recordExportFailure();
                }
            });
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
