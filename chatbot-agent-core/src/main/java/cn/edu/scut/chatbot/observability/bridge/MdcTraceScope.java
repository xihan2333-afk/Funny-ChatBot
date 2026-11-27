package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.loop.events.LoopEvent;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

/**
 * MDC 链路标识协同（observability-bridge 契约第 6 节、T021）：事件桥随 Span 激活同步写
 * MDC（traceId / spanId / groupId / turnId），Span 结束 try-finally 清理——虚拟线程复用
 * 不串味（本桥的消费线程跨事件复用，残留 MDC 会把上一事件的链路标识带进下一事件的日志）。
 *
 * <p>字段名与 javaagent 的 logback 增强一致（traceId / spanId），双机制兜底：javaagent
 * 在场时业务线程的日志由其增强注入，桥写的是自己消费线程上的 MDC（桥自身日志的链路标识）；
 * 缺席时本机制是唯一来源。
 *
 * <p>边界纪律（写在配置文件头与这里，面向读日志的人）：日志给人看；Decision 留档、转录、
 * llm_call_log、Span 是事实源——不解析日志做统计。
 */
public final class MdcTraceScope implements AutoCloseable {

    public static final String KEY_TRACE_ID = "traceId";
    public static final String KEY_SPAN_ID = "spanId";
    public static final String KEY_GROUP_ID = "groupId";
    public static final String KEY_TURN_ID = "turnId";

    private final boolean wroteAnything;

    private MdcTraceScope(boolean wroteAnything) {
        this.wroteAnything = wroteAnything;
    }

    /**
     * 空形态（无可写维度时不碰 MDC——关闭亦零操作）
     */
    public static MdcTraceScope noop() {
        return new MdcTraceScope(false);
    }

    /**
     * 激活链路标识（invalid 上下文跳过 traceId / spanId，群维度可空跳过）——
     * 调用方 try-finally 关闭（自动清理，不依赖调用点自觉）
     */
    public static MdcTraceScope activate(SpanContext spanContext, LoopEvent.RunIdentity identity) {
        boolean wrote = false;
        if (spanContext != null && spanContext.isValid()) {
            MDC.put(KEY_TRACE_ID, spanContext.getTraceId());
            MDC.put(KEY_SPAN_ID, spanContext.getSpanId());
            wrote = true;
        }
        if (identity != null) {
            if (identity.groupId() != null) {
                MDC.put(KEY_GROUP_ID, identity.groupId());
                wrote = true;
            }
            if (identity.turnId() != null) {
                MDC.put(KEY_TURN_ID, String.valueOf(identity.turnId()));
                wrote = true;
            }
        }
        return new MdcTraceScope(wrote);
    }

    @Override
    public void close() {
        if (wroteAnything) {
            MDC.remove(KEY_TRACE_ID);
            MDC.remove(KEY_SPAN_ID);
            MDC.remove(KEY_GROUP_ID);
            MDC.remove(KEY_TURN_ID);
        }
    }
}
