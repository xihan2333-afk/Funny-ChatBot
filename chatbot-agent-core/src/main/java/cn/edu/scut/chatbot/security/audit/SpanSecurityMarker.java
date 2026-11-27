package cn.edu.scut.chatbot.security.audit;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Span 安全标记（T089，security-gate 契约第 8 节）：
 * 在工具执行 Span 上挂三个标准安全属性——一次拦截同时落两处：
 * {@code security_event} 表供告警与统计、工具 Span 供链路页现场还原。
 *
 * <ul>
 *   <li>{@code chatbot.security.intercepted = true}</li>
 *   <li>{@code chatbot.security.rule = <命中规则/错误码>}</li>
 *   <li>{@code chatbot.security.channel = <通道：beforeToolCall / outboundScreen / ...>}</li>
 * </ul>
 */
@Component
public class SpanSecurityMarker {

    public static final String ATTR_INTERCEPTED = "chatbot.security.intercepted";
    public static final String ATTR_RULE = "chatbot.security.rule";
    public static final String ATTR_CHANNEL = "chatbot.security.channel";

    private static final String TRACER_NAME = "chatbot-security-audit";

    private final Supplier<Tracer> tracerSource;

    /**
     * 跨线程协同缓存（toolName → 最近一次拦截标记）：工具执行线程与事件桥消费线程分离，
     * EventBridgeSpanTranslator 在闭合 tool.{name} Span 前消费本标记挂三属性。
     */
    private final Map<String, SecurityMark> pendingByTool = new ConcurrentHashMap<>();

    public SpanSecurityMarker() {
        this(() -> GlobalOpenTelemetry.get().getTracer(TRACER_NAME));
    }

    /**
     * 测试构造：可注入内存 OTel SDK Tracer 断言三属性挂载
     */
    public SpanSecurityMarker(Supplier<Tracer> tracerSource) {
        this.tracerSource = tracerSource;
    }

    public record SecurityMark(String rule, String channel) {
    }

    /**
     * 直接给指定 Span 打三属性（EventBridgeSpanTranslator 协同与测试用）
     */
    public static void mark(Span span, String rule, String channel) {
        if (span == null) {
            return;
        }
        span.setAttribute(ATTR_INTERCEPTED, true);
        if (rule != null) {
            span.setAttribute(ATTR_RULE, rule);
        }
        if (channel != null) {
            span.setAttribute(ATTR_CHANNEL, channel);
        }
    }

    /**
     * 挂到当前线程活跃 Span（存在即打标）并按群维度 + 工具名登记跨线程协同标记（防异群并发串标）
     */
    public void markToolInterception(String platform, String botId, String groupId,
                                     String toolName, String rule, String channel) {
        Span current = Span.current();
        if (current != null && current.isRecording()) {
            mark(current, rule, channel);
        }
        if (toolName != null) {
            pendingByTool.put(scopedKey(platform, botId, groupId, toolName), new SecurityMark(rule, channel));
        }
    }

    /**
     * 兼容无群维度入口
     */
    public void markToolInterception(String toolName, String rule, String channel) {
        markToolInterception(null, null, null, toolName, rule, channel);
    }

    /**
     * beforeToolCall 拦截专用：因为拦截发生在工具 execute 之前，不会触发 ToolEvent，
     * 本方法在当前上下文下发射一条短 tool.{name} Span 并挂三属性，保证链路页可见拦截现场。
     */
    public void emitInterceptedToolSpan(String toolName, String rule, String channel,
                                        String platform, String botId, String groupId) {
        Span span = tracerSource.get().spanBuilder("tool." + (toolName == null ? "unknown" : toolName))
                .startSpan();
        try {
            if (platform != null) {
                span.setAttribute("chatbot.group.platform", platform);
            }
            if (botId != null) {
                span.setAttribute("chatbot.group.bot_id", botId);
            }
            if (groupId != null) {
                span.setAttribute("chatbot.group.group_id", groupId);
            }
            span.setAttribute("chatbot.is_error", true);
            mark(span, rule, channel);
        } finally {
            span.end();
        }
    }

    /**
     * 事件桥在闭合 tool.{name} Span 时按群维度 + 工具名弹出待挂标记（消费即移除，防并发串标与泄漏）
     */
    public SecurityMark consumePending(String platform, String botId, String groupId, String toolName) {
        if (toolName == null) {
            return null;
        }
        SecurityMark mark = pendingByTool.remove(scopedKey(platform, botId, groupId, toolName));
        return mark != null ? mark : pendingByTool.remove(scopedKey(null, null, null, toolName));
    }

    public SecurityMark consumePending(String toolName) {
        return consumePending(null, null, null, toolName);
    }

    private static String scopedKey(String platform, String botId, String groupId, String toolName) {
        return (platform != null ? platform : "") + "|" + (botId != null ? botId : "")
                + "|" + (groupId != null ? groupId : "") + "|" + toolName;
    }
}
