package cn.edu.scut.chatbot.observability.bridge;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;

/**
 * 跨进程 traceContext 接续（observability-bridge 契约第 5 节、T015）：主服务侧自有的
 * W3C propagator 显式实例（与接入层 TraceContextHelper 同模式、互不依赖——接入层不感知
 * 本类，本类不依赖接入层）。
 *
 * <p>形态：{@code 00-{traceId}-{spanId}-{flags}}。非法值容忍失败（返回 null / 不写入）——
 * 接入层旧版本信封不带 traceContext、构造方写坏值都不断链主流程。
 */
public final class TracePropagation {

    /**
     * 显式 W3C 实例（不用 GlobalOpenTelemetry 的默认注册——propagator 选择是本服务的显式决策）
     */
    public static final io.opentelemetry.context.propagation.TextMapPropagator W3C =
            W3CTraceContextPropagator.getInstance();

    private TracePropagation() {
    }

    /**
     * 解析 traceparent 为远端父 SpanContext（非法返回 null——容忍失败纪律）
     */
    public static SpanContext extract(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length != 4 || !"00".equals(parts[0])) {
            return null;
        }
        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];
        if (!TraceId.isValid(traceId) || !SpanId.isValid(spanId)) {
            return null;
        }
        try {
            TraceFlags traceFlags = TraceFlags.fromHex(flags, 0);
            return SpanContext.createFromRemoteParent(traceId, spanId, traceFlags,
                    io.opentelemetry.api.trace.TraceState.getDefault());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SpanContext → traceparent 原文（写入出事件信封用；invalid 上下文返回 null 不发假值）
     */
    public static String format(SpanContext context) {
        if (context == null || !context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-01";
    }
}
