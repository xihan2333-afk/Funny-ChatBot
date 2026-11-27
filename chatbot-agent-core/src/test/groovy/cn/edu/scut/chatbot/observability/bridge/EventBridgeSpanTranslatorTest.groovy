// 守护纪律：翻译表口径逐事件固化（observability-bridge 契约第 3 节、宪法七必测清单）——
// Span 名、chatbot.*/gen_ai.* 两级属性键值、跨进程接续 traceId、版本三键挂 run、
// turn/task 闭合语义不随重构漂移
package cn.edu.scut.chatbot.observability.bridge

import cn.edu.scut.chatbot.bootstrap.SystemParamMapper
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.engine.events.EngineEvent
import cn.edu.scut.chatbot.fixtures.InMemorySpanExporter
import cn.edu.scut.chatbot.loop.RunConfig
import cn.edu.scut.chatbot.loop.events.LoopEvent
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class EventBridgeSpanTranslatorTest extends Specification {

    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
    static final String REMOTE_SPAN_ID = "00f067aa0ba902b7"
    static final String TRACEPARENT = "00-" + TRACE_ID + "-" + REMOTE_SPAN_ID + "-01"

    InMemorySpanExporter exporter = new InMemorySpanExporter()
    SdkTracerProvider tracerProvider
    EventBridgeSpanTranslator translator
    SpanClosureRegistry registry

    void setup() {
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        def sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        def paramService = new SystemParamService(Mock(SystemParamMapper))
        registry = new SpanClosureRegistry(paramService,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai")))
        translator = new EventBridgeSpanTranslator(registry, { -> sdk.getTracer("test") })
    }

    void cleanup() {
        tracerProvider?.close()
    }

    def "IntakeRegistered 翻译为 intake Span 带 dedupKey 事件类型与接入层同 traceId"() {
        when:
        translator.onEvent(new EngineEvent.IntakeRegistered(
                "qq", "10000", "80001", "dk-1", "group_message", TRACEPARENT))

        then:
        def intake = exporter.firstByName("intake")
        intake != null
        intake.traceId == TRACE_ID
        intake.attributes.get(AttributeKey.stringKey("chatbot.dedup_key")) == "dk-1"
        intake.attributes.get(AttributeKey.stringKey("chatbot.event_type")) == "group_message"
        intake.attributes.get(AttributeKey.stringKey("chatbot.group.platform")) == "qq"
        intake.attributes.get(AttributeKey.stringKey("chatbot.group.bot_id")) == "10000"
        intake.attributes.get(AttributeKey.stringKey("chatbot.group.group_id")) == "80001"
    }

    def "L0 ignore 决策只产生 gate.l0 Span 不开 turn"() {
        when:
        translator.onEvent(new EngineEvent.DecisionMade(
                "qq", "10000", "80001", 9L, null, "ignore", "冷却期内", "L0", TRACEPARENT))

        then:
        def gate = exporter.firstByName("gate.l0")
        gate != null
        gate.traceId == TRACE_ID
        gate.attributes.get(AttributeKey.stringKey("chatbot.decision")) == "ignore"
        gate.attributes.get(AttributeKey.stringKey("chatbot.reason")) == "冷却期内"
        gate.attributes.get(AttributeKey.stringKey("chatbot.layer")) == "L0"
        gate.attributes.get(AttributeKey.longKey("chatbot.trigger_id")) == 9L
        exporter.firstByName("turn") == null
    }

    def "L1 决策产生 gate.l1 Span（按来源层级命名）"() {
        when:
        translator.onEvent(new EngineEvent.DecisionMade(
                "qq", "10000", "80001", 9L, null, "ignore", "话题无关", "L1", null))

        then:
        exporter.firstByName("gate.l1") != null
        exporter.firstByName("gate.l0") == null
    }

    def "accept 决策开 turn Span 且 run 与其子 Span 全程同一 traceId 版本三键挂 run"() {
        when: "完整剧本：决策接续 → run → 轮次 → 调用 → 工具 → 收尾 → 闭合"
        translator.onEvent(new EngineEvent.DecisionMade(
                "qq", "10000", "80001", 77L, 9L, "accept", "被 @提及", "L0", TRACEPARENT))
        translator.onEvent(LoopEvent.RunEvent.started(100L, RunConfig.ExecutionForm.TURN,
                new RunConfig.VersionKeys(3L, 2L, 1L),
                new LoopEvent.RunIdentity("qq", "10000", "80001", 9L, null), false))
        translator.onEvent(new LoopEvent.TurnEvent(100L, LoopEvent.Phase.START, 0))
        translator.onEvent(LoopEvent.MessageEvent.completed(100L, "m-1",
                new LoopEvent.UsageSummary("glm-4.6", "chat_main", "zai",
                        90L, 12L, 0L, 420L, "toolUse", null)))
        translator.onEvent(LoopEvent.ToolEvent.started(100L, "call-1", "sendMessage"))
        translator.onEvent(LoopEvent.ToolEvent.ended(100L, "call-1", "sendMessage",
                false, "{\"text\":\"收到\"}", "outbox-88", 15L))
        translator.onEvent(new LoopEvent.TurnEvent(100L, LoopEvent.Phase.END, 0))
        translator.onEvent(LoopEvent.RunEvent.ended(100L, "natural", List.of("outbox-88"), null))
        translator.onEvent(new EngineEvent.TurnTransitioned(
                "qq", "10000", "80001", 9L, "SETTLING", "SETTLED", List.of("outbox-88")))

        then: "长生命周期 Span 随闭合导出——先取全量再逐项断言"
        def turnSpan = exporter.firstByName("turn")
        def run = exporter.firstByName("run")
        def turn0 = exporter.firstByName("turn#0")
        def llmCall = exporter.firstByName("llm.call")
        def tool = exporter.firstByName("tool.sendMessage")
        turnSpan != null && run != null && turn0 != null && llmCall != null && tool != null

        and: "全程同一 traceId（跨进程接续）；父子链 turn → run → turn#0 → llm.call / tool"
        [turnSpan, run, turn0, llmCall, tool]*.traceId.every { it == TRACE_ID }
        run.parentSpanId == turnSpan.spanId
        turn0.parentSpanId == run.spanId
        llmCall.parentSpanId == turn0.spanId
        tool.parentSpanId == turn0.spanId

        and: "版本三键挂 run Span（重放版本对照的口径来源）"
        run.attributes.get(AttributeKey.longKey("chatbot.run_id")) == 100L
        run.attributes.get(AttributeKey.stringKey("chatbot.form")) == "TURN"
        run.attributes.get(AttributeKey.longKey("chatbot.turn_id")) == 9L
        run.attributes.get(AttributeKey.longKey("chatbot.version.promptFragment")) == 3L
        run.attributes.get(AttributeKey.longKey("chatbot.version.persona")) == 2L
        run.attributes.get(AttributeKey.longKey("chatbot.version.slotBinding")) == 1L
        run.attributes.get(AttributeKey.stringKey("chatbot.stop_reason")) == "natural"
        run.attributes.get(AttributeKey.longKey("chatbot.speech_count")) == 1L

        and: "llm.call 两级属性齐备（chatbot.* 口径 + gen_ai.* 生态兼容）"
        llmCall.attributes.get(AttributeKey.stringKey("chatbot.slot")) == "chat_main"
        llmCall.attributes.get(AttributeKey.longKey("chatbot.ttft_ms")) == 420L
        llmCall.attributes.get(AttributeKey.stringKey("chatbot.message_id")) == "m-1"
        llmCall.attributes.get(AttributeKey.stringKey("gen_ai.request.model")) == "glm-4.6"
        llmCall.attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")) == 90L
        llmCall.attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")) == 12L
        llmCall.attributes.get(AttributeKey.longKey("gen_ai.usage.cached_tokens")) == 0L

        and: "tool Span 带参数摘要、outboxId 与耗时"
        tool.attributes.get(AttributeKey.stringKey("chatbot.outbox_id")) == "outbox-88"
        tool.attributes.get(AttributeKey.booleanKey("chatbot.is_error")) == false
        tool.attributes.get(AttributeKey.longKey("chatbot.duration_ms")) == 15L
        tool.attributes.get(AttributeKey.stringKey("chatbot.param_digest")).contains("text")

        and: "turn 正常闭合（abnormal_end=false 显式留痕）且注册表清空"
        turnSpan.attributes.get(AttributeKey.booleanKey("chatbot.abnormal_end")) == false
        turnSpan.attributes.get(AttributeKey.stringKey("chatbot.status")) == "SETTLED"
        registry.pendingCount() == 0
    }

    def "失败调用的 MessageEvent 同样产生 llm.call 且 error_code 归一化挂属性"() {
        given:
        translator.onEvent(new EngineEvent.DecisionMade(
                "qq", "10000", "80001", 77L, 9L, "accept", "被 @提及", "L0", TRACEPARENT))
        translator.onEvent(LoopEvent.RunEvent.started(101L, RunConfig.ExecutionForm.TURN, null,
                new LoopEvent.RunIdentity("qq", "10000", "80001", 9L, null), false))

        when:
        translator.onEvent(LoopEvent.MessageEvent.failed(101L,
                new LoopEvent.UsageSummary("glm-4.6", "chat_main", "zai",
                        0L, 0L, 0L, 0L, "error", "llm.rate_limited")))

        then:
        def llmCall = exporter.firstByName("llm.call")
        llmCall != null
        llmCall.attributes.get(AttributeKey.stringKey("chatbot.error_code")) == "llm.rate_limited"
        llmCall.attributes.get(AttributeKey.stringKey("chatbot.stop_reason")) == "error"
    }

    def "Task 创建开 task Span 终态迁移正常闭合"() {
        when:
        translator.onEvent(new EngineEvent.TaskTransitioned(
                "qq", "10000", "80001", 55L, null, "SCHEDULED", "member"))

        then: "创建后是在途态（长生命周期 Span 只在闭合时导出）"
        registry.pendingCount() == 1

        when:
        translator.onEvent(new EngineEvent.TaskTransitioned(
                "qq", "10000", "80001", 55L, "RUNNING", "DONE", "member"))

        then:
        def task = exporter.firstByName("task")
        task != null
        task.attributes.get(AttributeKey.longKey("chatbot.task_id")) == 55L
        task.attributes.get(AttributeKey.stringKey("chatbot.mandate")) == "member"
        task.attributes.get(AttributeKey.booleanKey("chatbot.abnormal_end")) == false
        task.attributes.get(AttributeKey.stringKey("chatbot.status")) == "DONE"
        registry.pendingCount() == 0
    }

    def "EffectExecuted 翻译为 effect.kind Span 带结局与重试数"() {
        when:
        translator.onEvent(new EngineEvent.EffectExecuted(
                "qq", "10000", "80001", "generation", "success", 0))

        then:
        def span = exporter.firstByName("effect.generation")
        span != null
        span.attributes.get(AttributeKey.stringKey("chatbot.outcome")) == "success"
        span.attributes.get(AttributeKey.longKey("chatbot.retry_count")) == 0L
    }

    def "DeliverySettled 挂回原出事件的 trace 带 outboxId 与回执状态"() {
        when:
        translator.onEvent(new EngineEvent.DeliverySettled(
                "qq", "10000", "80001", 88L, "settled", TRACE_ID, TRACEPARENT))

        then:
        def span = exporter.firstByName("delivery.settle")
        span != null
        span.traceId == TRACE_ID
        span.attributes.get(AttributeKey.longKey("chatbot.outbox_id")) == 88L
        span.attributes.get(AttributeKey.stringKey("chatbot.settle_status")) == "settled"
        span.attributes.get(AttributeKey.stringKey("chatbot.trace_ref")) == TRACE_ID
    }

    def "非法 traceContext 容忍失败开根不抛异常"() {
        when:
        translator.onEvent(new EngineEvent.IntakeRegistered(
                "qq", "10000", "80001", "dk-bad", "group_message", "not-a-traceparent"))

        then:
        noExceptionThrown()
        def intake = exporter.firstByName("intake")
        intake != null
        intake.traceId != TRACE_ID
    }

    def "未知 runId 的循环事件安静跳过不产生 Span"() {
        when:
        translator.onEvent(new LoopEvent.TurnEvent(999L, LoopEvent.Phase.START, 0))
        translator.onEvent(LoopEvent.ToolEvent.started(999L, "call-x", "webSearch"))

        then:
        noExceptionThrown()
        exporter.finished().isEmpty()
    }
}
