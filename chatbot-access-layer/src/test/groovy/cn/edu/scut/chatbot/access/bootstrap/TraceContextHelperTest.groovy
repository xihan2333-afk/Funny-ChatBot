package cn.edu.scut.chatbot.access.bootstrap

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import spock.lang.Specification

/**
 * 守护纪律：traceId 贯穿「平台进 → 入流 → 出事件 → 回执」全程（quickstart US5 验收口径 +
 * research R6 决策）。W3C traceparent 的注入 / 提取用显式 propagator（不依赖 javaagent 对
 * WS 入口的自动埋点）；非法 traceContext 容忍提取失败不抛——坏数据不能中断翻译主流程。
 */
class TraceContextHelperTest extends Specification {

    def helper = new TraceContextHelper()

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    private static final String SPAN_ID = "00f067aa0ba902b7"

    private Context contextWithFixedSpan() {
        def spanContext = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault())
        return Context.current().with(Span.wrap(spanContext))
    }

    def "固定SpanContext下注入产出合法W3C traceparent字符串"() {
        given:
        def context = contextWithFixedSpan()

        when:
        def traceparent = helper.injectCurrent(context)

        then:
        traceparent == "00-" + TRACE_ID + "-" + SPAN_ID + "-01"
    }

    def "提取合法traceparent还原出父上下文的traceId与spanId"() {
        given:
        def traceparent = "00-" + TRACE_ID + "-" + SPAN_ID + "-01"

        when:
        def extracted = helper.extract(traceparent)

        then:
        def spanContext = Span.fromContext(extracted).spanContext
        spanContext.traceId == TRACE_ID
        spanContext.spanId == SPAN_ID
    }

    def "提取非法traceContext容忍失败不抛返回无效上下文"() {
        when:
        def fromNull = helper.extract(null)
        def fromGarbage = helper.extract("这不是traceparent")
        def fromBroken = helper.extract("00-short-short-01")

        then: "不抛异常，提取结果是无效 Span（容错：坏数据不中断主流程）"
        noExceptionThrown()
        !Span.fromContext(fromNull).spanContext.isValid()
        !Span.fromContext(fromGarbage).spanContext.isValid()
        !Span.fromContext(fromBroken).spanContext.isValid()
    }
}
