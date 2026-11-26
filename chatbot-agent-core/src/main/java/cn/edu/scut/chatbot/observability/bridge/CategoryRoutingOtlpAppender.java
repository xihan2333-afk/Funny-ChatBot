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
        MDC.put(LOG_CATEGORY_KEY, categoryOf(event.getLoggerName()));
        try {
            super.append(event);
        } finally {
            MDC.remove(LOG_CATEGORY_KEY);
        }
    }

    /**
     * 类别判定（包可见：单测直接断言判定口径）
     */
    static String categoryOf(String loggerName) {
        return loggerName != null && loggerName.startsWith(AGENT_LOGGER_PREFIX) ? "agent" : "system";
    }
}
