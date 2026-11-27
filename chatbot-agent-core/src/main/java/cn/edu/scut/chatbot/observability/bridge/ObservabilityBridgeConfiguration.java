package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.engine.events.EngineEventPublisher;
import cn.edu.scut.chatbot.infrastructure.eventbus.LoopEventWiring;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;

/**
 * 观测事件桥装配（T012/T014 接线）：引擎事件发布器 → 桥外壳（有界队列）→ 翻译器；
 * 循环事件经 LoopEventWiring 全局消费方注册把翻译器挂进每 Run 的监听器。
 * 依赖方向：observability → engine / loop / infrastructure（合法消费边，不动 loop 内核）。
 */
@Configuration
public class ObservabilityBridgeConfiguration {

    /**
     * 引擎事件桥外壳（应用级单例——引擎事件不分 Run，turn/task 长生命周期与
     * Effects 事件都进同一队列；丢弃计数喂 ExportHealthMetrics）
     */
    @Bean
    public EngineEventBridgeShell engineEventBridgeShell(ExportHealthMetrics exportHealth) {
        return new EngineEventBridgeShell(exportHealth);
    }

    /**
     * 翻译器（Tracer 永远经全局入口——javaagent 与自建 SDK 拿到同一实例）
     */
    @Bean
    public EventBridgeSpanTranslator eventBridgeSpanTranslator(SpanClosureRegistry closureRegistry) {
        return new EventBridgeSpanTranslator(closureRegistry);
    }

    /**
     * 接线（bean 创建顺序保证三方就绪后一次挂好）：发布器 → 外壳 → 翻译器；
     * 循环事件经全局消费方注册口挂进每 Run 监听器
     */
    @Bean
    public ObservabilityBridgeWiring observabilityBridgeWiring(EngineEventPublisher publisher,
                                                               EngineEventBridgeShell bridgeShell,
                                                               EventBridgeSpanTranslator translator,
                                                               LoopEventWiring loopEventWiring) {
        publisher.addListener(bridgeShell);
        bridgeShell.addBackgroundConsumer(translator);
        loopEventWiring.registerGlobalConsumer(translator);
        // OTLP 日志 appender 装配：全局 OTel 实例（javaagent 在场 = agent 的；缺席 = Bootstrap
        // 自建兜底的；noop 下日志导出为空转）。append 们不配置 openTelemetry 即消费此实例。
        try {
            io.opentelemetry.api.OpenTelemetry otel = GlobalOpenTelemetry.get();
            OpenTelemetryAppender.install(otel);
            installNestedOtlpAppenders(otel);
        } catch (Exception installFailure) {
            // 日志外发缺席不阻断启动（可丢不可卡——FR-026）
        }
        return new ObservabilityBridgeWiring(bridgeShell);
    }

    /**
     * OpenTelemetryAppender.install 只扫 Logger 直接挂载的 appender，不会钻进 AsyncAppender；
     * logback-spring.xml 里 OTLP_RAW 包在 OTLP_ASYNC 内，需递归下钻安装（否则 log.category 与 MDC 不外发）。
     */
    @SuppressWarnings("unchecked")
    private static void installNestedOtlpAppenders(io.opentelemetry.api.OpenTelemetry otel) {
        org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
        if (!(factory instanceof ch.qos.logback.classic.LoggerContext ctx)) {
            return;
        }
        for (ch.qos.logback.classic.Logger logger : ctx.getLoggerList()) {
            java.util.Iterator<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it =
                    logger.iteratorForAppenders();
            while (it.hasNext()) {
                walkAppender(it.next(), otel);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void walkAppender(ch.qos.logback.core.Appender<?> appender,
                                     io.opentelemetry.api.OpenTelemetry otel) {
        if (appender instanceof OpenTelemetryAppender otlp) {
            otlp.setOpenTelemetry(otel);
            return;
        }
        if (appender instanceof ch.qos.logback.core.spi.AppenderAttachable<?> attachable) {
            java.util.Iterator<? extends ch.qos.logback.core.Appender<?>> nested =
                    attachable.iteratorForAppenders();
            while (nested.hasNext()) {
                walkAppender(nested.next(), otel);
            }
        }
    }

    /**
     * 应用关闭时排空桥外壳（残余事件送达后才退出——观测事件尽量送达纪律）
     */
    public static final class ObservabilityBridgeWiring {

        private final EngineEventBridgeShell shell;

        ObservabilityBridgeWiring(EngineEventBridgeShell shell) {
            this.shell = shell;
        }

        @PreDestroy
        void closeShell() {
            shell.close();
        }
    }
}
