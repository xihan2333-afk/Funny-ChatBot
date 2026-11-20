package cn.edu.scut.chatbot.access.bootstrap;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * traceContext 注入 / 提取工具（research R6 决策）：W3C traceparent 的生成与解析。
 *
 * <p>刻意用显式的 {@link W3CTraceContextPropagator} 而非 GlobalOpenTelemetry 的组合
 * propagator——行为完全由 API 层决定，不依赖 javaagent 是否挂载、也不依赖 agent 的
 * propagator 配置；SDK（真正的 Span 采样与导出）由各进程的 -javaagent 安装。
 *
 * <p>非法 traceContext 的提取容忍失败（返回无效上下文）——坏数据不中断翻译与投递主流程。
 */
@Component
public class TraceContextHelper {

    private static final String TRACEPARENT_HEADER = "traceparent";

    private final W3CTraceContextPropagator propagator = W3CTraceContextPropagator.getInstance();

    private static final TextMapSetter<Map<String, String>> MAP_SETTER =
            (carrier, key, value) -> {
                if (carrier != null) {
                    carrier.put(key, value);
                }
            };

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? java.util.List.of() : carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    /**
     * 把当前上下文的 trace 信号注入为 traceparent 字符串（写入信封 traceContext 字段）
     */
    public String injectCurrent(Context context) {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context == null ? Context.current() : context, carrier, MAP_SETTER);
        return carrier.get(TRACEPARENT_HEADER);
    }

    /**
     * 从 traceparent 字符串提取父上下文（非法 / null 容忍失败，返回不含有效 Span 的上下文）
     */
    public Context extract(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return propagator.extract(Context.root(), Map.of(TRACEPARENT_HEADER, traceparent), MAP_GETTER);
    }
}
