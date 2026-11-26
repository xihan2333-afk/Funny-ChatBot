// 守护纪律：跨时刻闭合注册表的兜底语义（observability-bridge 契约第 4 节、research R4）——
// 结束事件丢失时 Span 不永不闭合（污染聚合口径），超时强制闭合打 abnormal_end 且计数可告警
package cn.edu.scut.chatbot.observability.bridge

import cn.edu.scut.chatbot.bootstrap.SystemParamMapper
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.InMemorySpanExporter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import spock.lang.Specification

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class SpanClosureRegistryTest extends Specification {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z")

    InMemorySpanExporter exporter = new InMemorySpanExporter()
    SdkTracerProvider tracerProvider
    SpanClosureRegistry registry

    void setup() {
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        def sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        def paramService = new SystemParamService(Mock(SystemParamMapper))
        registry = new SpanClosureRegistry(paramService, Clock.fixed(T0, ZoneId.of("Asia/Shanghai")))
        tracer = sdk.getTracer("test")
    }

    def tracer

    void cleanup() {
        tracerProvider?.close()
    }

    def "结束事件到达正常闭合 abnormal_end=false 显式留痕"() {
        given:
        def span = tracer.spanBuilder("turn").startSpan()
        registry.register("turn:1", span, Duration.ofMinutes(10))

        when:
        registry.close("turn:1")

        then:
        registry.pendingCount() == 0
        def data = exporter.firstByName("turn")
        data != null
        data.attributes.get(AttributeKey.booleanKey("chatbot.abnormal_end")) == false
        registry.abnormalEndCount() == 0L
    }

    def "超时拨钟强制闭合打 abnormal_end=true 计数暴露给告警探针"() {
        given:
        def span = tracer.spanBuilder("turn").startSpan()
        registry.register("turn:2", span, Duration.ofMinutes(10))

        when: "时钟拨过截止时刻（FixedClock 语义——forceCloseExpired 显式收 now）"
        def forced = registry.forceCloseExpired(T0.plus(Duration.ofMinutes(11)))

        then:
        forced == 1L
        registry.pendingCount() == 0
        def data = exporter.firstByName("turn")
        data != null
        data.attributes.get(AttributeKey.booleanKey("chatbot.abnormal_end")) == true
        registry.abnormalEndCount() == 1L
    }

    def "未超时的在途 Span 不被扫描波及"() {
        given:
        def span = tracer.spanBuilder("turn").startSpan()
        registry.register("turn:3", span, Duration.ofMinutes(10))

        when:
        def forced = registry.forceCloseExpired(T0.plus(Duration.ofMinutes(5)))

        then:
        forced == 0L
        registry.pendingCount() == 1
        registry.close("turn:3")
        registry.pendingCount() == 0
    }

    def "turn 与 task 兜底超时从 system_param 读 缺省 10 分钟与 48 小时"() {
        expect:
        registry.turnTimeout() == Duration.ofMinutes(10)
        registry.taskTimeout() == Duration.ofHours(48)
    }
}
