// 守护纪律：W3C traceparent 写入解析往返与非法容忍（observability-bridge 契约第 5 节、
// SC-001 的跨进程接续基础）——旧信封不带、构造方写坏值都不断链主流程
package cn.edu.scut.chatbot.observability.bridge

import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import spock.lang.Specification

class TracePropagationTest extends Specification {

    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    static final String SPAN_ID = "00f067aa0ba902b7"

    def "SpanContext → traceparent → 解析回同一 SpanContext（写入解析往返）"() {
        given:
        def original = SpanContext.createFromRemoteParent(
                TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault())

        when:
        def text = TracePropagation.format(original)
        def parsed = TracePropagation.extract(text)

        then:
        text == "00-" + TRACE_ID + "-" + SPAN_ID + "-01"
        parsed != null
        parsed.traceId == TRACE_ID
        parsed.spanId == SPAN_ID
        parsed.isRemote()
    }

    def "非法与缺省输入容忍失败返回 null"() {
        expect:
        TracePropagation.extract(null) == null
        TracePropagation.extract("") == null
        TracePropagation.extract("not-a-traceparent") == null
        TracePropagation.extract("00-short-short-01") == null
        TracePropagation.extract("99-" + TRACE_ID + "-" + SPAN_ID + "-01") == null
        TracePropagation.format(null) == null
        TracePropagation.format(SpanContext.getInvalid()) == null
    }
}
