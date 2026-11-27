package cn.edu.scut.chatbot.observability.bridge;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.MDC;

/**
 * 按日志类别附加 log.category 属性的 OTLP appender（observability-bridge 契约第 6 节、
 * T020）：logger 名以 agent. 开头的（Agent 业务过程日志）打 log.category=agent，其余
 * 打 log.category=system——Collector 的 routing connector 凭此把日志路由到
 * logs-agent-* / logs-system-* 两个数据流（物理分离的写入面）。
 *
 * <p>实现方式：append 前把类别写进 MDC、append 后移除——OTLP appender 配置
 * {@code captureMdcAttributes=log.category} 后会把它采集为日志属性。这是对既有
 * appender 行为（MDC → 属性）的窄面复用，不改其内部。
 */
public final class CategoryRoutingOtlpAppender extends OpenTelemetryAppender {

    /**
     * 日志属性键（Collector 路由依据）
     */
    public static final String LOG_CATEGORY_KEY = "log.category";

    /**
     * Agent 业务过程 logger 前缀（logback-spring.xml 里同名 logger 绑 agent 文件）
     */
    public static final String AGENT_LOGGER_PREFIX = "agent.";

    @Override
    protected void append(ILoggingEvent event) {
        String category = categoryOf(event.getLoggerName());
        MDC.put(LOG_CATEGORY_KEY, category);
        try {
            // AsyncAppender 在调用线程冻结了 event.getMDCPropertyMap()，工作线程只改 MDC 不会反映到快照；
            // 用轻量动态代理合并 log.category，确保同步与异步两条路径都带该属性。
            ILoggingEvent enriched = enrichCategory(event, category);
            super.append(enriched);
        } finally {
            MDC.remove(LOG_CATEGORY_KEY);
        }
    }

    private static ILoggingEvent enrichCategory(ILoggingEvent event, String category) {
        java.util.Map<String, String> orig = event.getMDCPropertyMap();
        if (orig != null && category.equals(orig.get(LOG_CATEGORY_KEY))) {
            return event;
        }
        java.util.Map<String, String> merged = new java.util.HashMap<>();
        if (orig != null) {
            merged.putAll(orig);
        }
        merged.put(LOG_CATEGORY_KEY, category);
        java.util.Map<String, String> snapshot = java.util.Collections.unmodifiableMap(merged);
        return (ILoggingEvent) java.lang.reflect.Proxy.newProxyInstance(
                ILoggingEvent.class.getClassLoader(),
                new Class<?>[]{ILoggingEvent.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getMDCPropertyMap".equals(name) || "getMdc".equals(name)) {
                        return snapshot;
                    }
                    return method.invoke(event, args);
                });
    }

    /**
     * 钳制安装前缓存条数（FR-026 观测装配失败不阻断业务）：父类 1.33.6-alpha 的
     * start() 直接拿该值当 ArrayBlockingQueue 容量，0 或负数会抛 IllegalArgumentException
     * ——而 logback 配置错误会被 Spring Boot 升级为启动失败，观测装配反过来拖垮业务。
     * 父类 setter 原样存储不做校验，这里在入口把非法值钳到最小合法容量 1。
     */
    @Override
    public void setNumLogsCapturedBeforeOtelInstall(int numLogsCapturedBeforeOtelInstall) {
        super.setNumLogsCapturedBeforeOtelInstall(Math.max(1, numLogsCapturedBeforeOtelInstall));
    }

    /**
     * 类别判定（包可见：单测直接断言判定口径）
     */
    static String categoryOf(String loggerName) {
        return loggerName != null && loggerName.startsWith(AGENT_LOGGER_PREFIX) ? "agent" : "system";
    }
}
