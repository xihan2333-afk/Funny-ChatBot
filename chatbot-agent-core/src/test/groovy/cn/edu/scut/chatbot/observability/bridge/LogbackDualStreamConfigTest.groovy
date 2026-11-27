// 守护纪律：日志双流物理分离（设计方案 2.5.3、observability-bridge 契约第 6 节、T023）——
// agent. 前缀 logger 只进 agent 文件与 agent 数据流、root 进 system 文件与 system 数据流，
// log.category 属性按前缀附加（Collector 路由依据）；MDC 激活清理不串味（虚拟线程复用）
package cn.edu.scut.chatbot.observability.bridge

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.status.Status
import cn.edu.scut.chatbot.loop.events.LoopEvent
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import org.slf4j.MDC
import spock.lang.Specification

class LogbackDualStreamConfigTest extends Specification {

    @spock.lang.TempDir
    File logDir

    def "logback-spring.xml 双流拓扑加载：两类滚动文件与 logger 前缀映射就位"() {
        given: "LOG_HOME 指向临时目录（配置文件头声明的环境变量覆盖面）"
        System.setProperty("CHATBOT_LOG_HOME", logDir.absolutePath)
        def context = new LoggerContext()
        def configurator = new JoranConfigurator()
        configurator.setContext(context)
        def configUrl = getClass().classLoader.getResource("logback-spring.xml")
        assert configUrl != null: "logback-spring.xml 未进 test classpath"

        when:
        configurator.doConfigure(configUrl)

        then: "root 绑 system 文件 + OTLP 异步外发"
        def root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        (root.getAppender("SYSTEM_FILE") as RollingFileAppender).file.endsWith("system.log")
        root.getAppender("OTLP_ASYNC") != null

        and: "整份配置干净装载、OTel appender 真实 start 成功（对齐 Spring Boot 口径：ERROR status 会被升级为启动失败——005 IT 翻车根因）"
        def otlpRaw = (root.getAppender("OTLP_ASYNC") as AsyncAppender).getAppender("OTLP_RAW")
        otlpRaw != null && otlpRaw.started
        context.getStatusManager().getCopyOfStatusList()
                .findAll { it.getLevel() == Status.ERROR }.isEmpty()

        and: "agent. 前缀四个 logger 绑 agent 文件，不向 root 重复写（additivity=false）"
        for (name in ["agent.decision", "agent.generation", "agent.tool", "agent.task"]) {
            def logger = context.getLogger(name)
            (logger.getAppender("AGENT_FILE") as FileAppender).file.endsWith("agent.log")
            logger.getAppender("OTLP_ASYNC") != null
            !logger.isAdditive()
        }

        and: "agent logger 不挂 SYSTEM_FILE、root 不挂 AGENT_FILE（物理分离）"
        context.getLogger("agent.decision").getAppender("SYSTEM_FILE") == null
        root.getAppender("AGENT_FILE") == null

        cleanup:
        context.stop()
        System.clearProperty("CHATBOT_LOG_HOME")
    }

    def "log.category 按 logger 前缀判定：agent 前缀 agent 其余 system"() {
        expect:
        CategoryRoutingOtlpAppender.categoryOf("agent.decision") == "agent"
        CategoryRoutingOtlpAppender.categoryOf("agent.generation") == "agent"
        CategoryRoutingOtlpAppender.categoryOf("cn.edu.scut.chatbot.loop.AgentLoop") == "system"
        CategoryRoutingOtlpAppender.categoryOf(null) == "system"
    }

    def "numLogsCapturedBeforeOtelInstall 非法值钳到 1：观测装配不阻断启动（FR-026）"() {
        given: "父类 1.33.6-alpha 的 start() 直接拿该值当 ArrayBlockingQueue 容量，0 会抛 IAE"
        def appender = new CategoryRoutingOtlpAppender()
        appender.setContext(new LoggerContext())
        appender.setName("OTLP_CLAMP")

        when:
        appender.setNumLogsCapturedBeforeOtelInstall(0)
        appender.start()

        then:
        noExceptionThrown()
        appender.isStarted()
    }

    def "MdcTraceScope 激活注入 traceId spanId groupId turnId 关闭即清理"() {
        given:
        def ctx = SpanContext.createFromRemoteParent(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                TraceFlags.getSampled(), TraceState.getDefault())
        def identity = new LoopEvent.RunIdentity("qq", "10000", "80001", 9L, null)

        when:
        def scope = MdcTraceScope.activate(ctx, identity)

        then:
        MDC.get("traceId") == "4bf92f3577b34da6a3ce929d0e0e4736"
        MDC.get("spanId") == "00f067aa0ba902b7"
        MDC.get("groupId") == "80001"
        MDC.get("turnId") == "9"

        when:
        scope.close()

        then:
        MDC.get("traceId") == null
        MDC.get("spanId") == null
        MDC.get("groupId") == null
        MDC.get("turnId") == null
    }

    def "连续两个不同 Turn 不串味：第二个激活看不到第一个的残留"() {
        given:
        def ctx = SpanContext.createFromRemoteParent(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                TraceFlags.getSampled(), TraceState.getDefault())

        when: "第一个 Turn 激活后关闭，第二个 Turn 激活"
        MdcTraceScope.activate(ctx,
                new LoopEvent.RunIdentity("qq", "10000", "80001", 9L, null)).close()
        def second = MdcTraceScope.activate(ctx,
                new LoopEvent.RunIdentity("qq", "10000", "80002", 10L, null))

        then: "只有第二 Turn 的维度在场"
        MDC.get("groupId") == "80002"
        MDC.get("turnId") == "10"

        when:
        second.close()

        then:
        MDC.get("groupId") == null
        MDC.get("turnId") == null
    }

    def "invalid SpanContext 与空维度是 noop 形态：不写任何键"() {
        when:
        def scope = MdcTraceScope.activate(SpanContext.getInvalid(), null)
        scope.close()

        then:
        noExceptionThrown()
        MDC.get("traceId") == null
        MDC.get("spanId") == null
        MDC.get("groupId") == null
    }
}
