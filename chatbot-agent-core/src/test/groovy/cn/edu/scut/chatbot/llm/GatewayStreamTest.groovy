// 守护纪律：内核拿到的永远是流内消息不是异常——网关按方言路由、一切失败编码为
// Failed 事件、取消经唯一通道、连接级静默重试一次（宪法第五条 + contracts/model-gateway.md）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream
import cn.edu.scut.chatbot.fixtures.StubTool
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder
import cn.edu.scut.chatbot.llm.gateway.*
import cn.edu.scut.chatbot.llm.slot.CostRecorder
import cn.edu.scut.chatbot.llm.slot.CredentialPool
import cn.edu.scut.chatbot.llm.slot.SlotResolver
import cn.edu.scut.chatbot.loop.*
import cn.edu.scut.chatbot.loop.hooks.LoopHooks
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector
import spock.lang.Specification

import java.net.http.HttpRequest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GatewayStreamTest extends Specification {

    def paramService = Mock(SystemParamService) {
        getLong(_, _) >> 300L
        getInt(_, _) >> 300
    }
    def holder = new HttpClientHolder(paramService)
    def exchange = new StreamExchange(holder)

    def modelOf(String dialect, String baseUrl = "http://127.0.0.1:1") {
        return new ModelRef(dialect, "zai", "glm-test", baseUrl,
                new ModelCapabilities(true, true, 128000, 4096), null, "chat_main")
    }

    def requestOf(ModelRef model) {
        return new ModelRequest(model, [cn.edu.scut.chatbot.loop.LlmMessage.user("你好")],
                "系统提示词", [], GenerationParams.defaults(), "sk-test")
    }

    /**
     * 测试翻译器：记录被调用与收到的 model——路由断言的观察面
     */
    class RecordingTranslator implements DialectTranslator {
        final String dialectName
        AtomicInteger calls = new AtomicInteger()
        List<ModelRef> seenModels = []

        RecordingTranslator(String dialectName) {
            this.dialectName = dialectName
        }

        String dialect() { dialectName }

        void translate(TranslateContext context) {
            calls.incrementAndGet()
            seenModels << context.request().model()
            context.consumer().onEvent(new ModelStreamEvent.Completed(new AssistantMessage(
                    "m-" + calls.get(), java.time.Instant.now(), 60, null,
                    "（" + dialectName + " 翻译器产出）", [], AssistantMessage.STOP_REASON_STOP,
                    AssistantMessage.TokenUsage.ZERO)))
        }
    }

    /**
     * 流式骨架翻译器：经 StreamExchange 把上游 SSE data 原样变 TextDelta——
     * 取消/重试/流中断场景走真骨架（US1 三层主干的协作证据）
     */
    class SsePassthroughTranslator implements DialectTranslator {
        final String dialectName
        List<String> failures = []

        SsePassthroughTranslator(String dialectName) {
            this.dialectName = dialectName
        }

        String dialect() { dialectName }

        void translate(TranslateContext context) {
            def model = context.request().model()
            def request = HttpRequest.newBuilder()
                    .uri(URI.create(model.baseUrl() + "/chat/completions"))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + context.request().resolvedCredential())
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build()
            exchange.exchange(request, new StreamExchange.ExchangeHandler() {
                @Override
                void onHttpError(int status, String contentType, String body) {
                    failures << "http:" + status
                    context.consumer().onEvent(new ModelStreamEvent.Failed("llm.api_error", "HTTP " + status))
                }

                @Override
                void onSseData(String data) {
                    if (data == "[DONE]") {
                        return
                    }
                    context.consumer().onEvent(new ModelStreamEvent.TextStart())
                    context.consumer().onEvent(new ModelStreamEvent.TextDelta(data))
                    context.consumer().onEvent(new ModelStreamEvent.TextEnd())
                }

                @Override
                void onStreamEnd() {
                    context.consumer().onEvent(new ModelStreamEvent.Completed(new AssistantMessage(
                            "done", java.time.Instant.now(), 60, null, "完成", [],
                            AssistantMessage.STOP_REASON_STOP, AssistantMessage.TokenUsage.ZERO)))
                }

                @Override
                void onIoFailure(String errorCode, String message) {
                    failures << errorCode
                    context.consumer().onEvent(new ModelStreamEvent.Failed(errorCode, message))
                }

                @Override
                void onAborted() {
                    failures << "llm.aborted"
                    context.consumer().onEvent(new ModelStreamEvent.Failed("llm.aborted", "取消终止"))
                }
            }, context.signal())
        }
    }

    def noopPool() {
        return new CredentialPool() {
            @Override
            String nextAvailable(String providerCode) { return null }

            @Override
            void feedback(String providerCode, String usedKeyMask, CredentialPool.FeedbackKind kind) {}
        }
    }

    def gateway(RecordingTranslator openai, RecordingTranslator gemini) {
        return new GatewayStreamFn(new DialectRegistry([openai, gemini]), noopPool(),
                Mock(SlotResolver), Mock(CostRecorder))
    }

    def "dialect 路由正确：openai-compat 与 gemini 各派发到对应翻译器"() {
        given:
        def openai = new RecordingTranslator(ModelRef.DIALECT_OPENAI_COMPAT)
        def gemini = new RecordingTranslator(ModelRef.DIALECT_GEMINI)
        def gateway = gateway(openai, gemini)
        def events = []

        when:
        gateway.stream(requestOf(modelOf(ModelRef.DIALECT_OPENAI_COMPAT)), { events << it }, CancellationSignal.create())

        then:
        openai.calls.get() == 1
        gemini.calls.get() == 0
        events.last() instanceof ModelStreamEvent.Completed

                when :
        gateway.stream(requestOf(modelOf(ModelRef.DIALECT_GEMINI)), { events << it }, CancellationSignal.create())

        then:
        gemini.calls.get() == 1
    }

    def "未知 dialect 以 Failed(llm.config_missing) 终止且不达翻译器"() {
        given:
        def openai = new RecordingTranslator(ModelRef.DIALECT_OPENAI_COMPAT)
        def gemini = new RecordingTranslator(ModelRef.DIALECT_GEMINI)
        def gateway = gateway(openai, gemini)
        def events = []

        when:
        gateway.stream(requestOf(modelOf("anthropic-claude")), { events << it }, CancellationSignal.create())

        then:
        openai.calls.get() == 0
        gemini.calls.get() == 0
        events.size() == 1
        events[0] instanceof ModelStreamEvent.Failed
                events[0].errorCode() == "llm.config_missing"
    }

    def "ModelRequest 不带 ModelRef 同样 config_missing（装配链缺失的兜底）"() {
        given:
        def openai = new RecordingTranslator(ModelRef.DIALECT_OPENAI_COMPAT)
        def gateway = gateway(openai, new RecordingTranslator(ModelRef.DIALECT_GEMINI))
        def events = []

        when:
        gateway.stream(requestOf(null), { events << it }, CancellationSignal.create())

        then:
        events[0].errorCode() == "llm.config_missing"
    }

    def "翻译器抛异常时网关兜底转 Failed 绝不外抛（对抗性负向）"() {
        given:
        def broken = new RecordingTranslator(ModelRef.DIALECT_OPENAI_COMPAT) {
            @Override
            void translate(TranslateContext context) {
                throw new IllegalStateException("翻译器内部 bug")
            }
        }
        def gateway = new GatewayStreamFn(new DialectRegistry([broken]), noopPool(), Mock(SlotResolver), Mock(CostRecorder))
        def events = []

        when:
        gateway.stream(requestOf(modelOf(ModelRef.DIALECT_OPENAI_COMPAT)), { events << it }, CancellationSignal.create())

        then: "调用方拿到 Failed 不是异常"
        noExceptionThrown()
        events.size() == 1
        events[0] instanceof ModelStreamEvent.Failed
                events[0].errorCode() == "llm.api_error"
    }

    def "连接拒绝首次静默重试一次再拒以 Failed(llm.network) 终结（连接计数 = 2）"() {
        given: "accept 即断的端点——客户端响应头未到达，属连接级失败"
        ScriptedHttpUpstream.RefusingEndpoint endpoint = ScriptedHttpUpstream.openRefusingEndpoint()
        def translator = new SsePassthroughTranslator(ModelRef.DIALECT_OPENAI_COMPAT)
        def events = []
        def signal = CancellationSignal.create()

        when:
        translator.translate(new TranslateContext(
                requestOf(modelOf(ModelRef.DIALECT_OPENAI_COMPAT, endpoint.baseUrl())),
                { events << it } as cn.edu.scut.chatbot.loop.StreamConsumer, signal))

        then:
        events.any { it instanceof ModelStreamEvent.Failed && it.errorCode() == "llm.network" }
        endpoint.acceptCount() == 2

        cleanup:
        endpoint.close()
    }

    def "取消信号在连接前置位则不发起请求直接 Failed(llm.aborted)"() {
        given:
        def endpoint = ScriptedHttpUpstream.openRefusingEndpoint()
        def translator = new SsePassthroughTranslator(ModelRef.DIALECT_OPENAI_COMPAT)
        def events = []
        def signal = CancellationSignal.create()
        signal.cancel()

        when:
        translator.translate(new TranslateContext(
                requestOf(modelOf(ModelRef.DIALECT_OPENAI_COMPAT, endpoint.baseUrl())),
                { events << it } as cn.edu.scut.chatbot.loop.StreamConsumer, signal))

        then:
        events.size() == 1
        events[0].errorCode() == "llm.aborted"
        endpoint.acceptCount() == 0

        cleanup:
        endpoint.close()
    }

    def "流中途置取消：已推增量保留、终止为 Failed(llm.aborted)"() {
        given: "上游先推一个分片再挂起"
        def upstream = new ScriptedHttpUpstream()
        upstream.addScript("/chat/completions", [
                new ScriptedHttpUpstream.SseChunks(["data: 前半增量\n\n"]),
                new ScriptedHttpUpstream.Hang()
        ])
        upstream.start()
        def translator = new SsePassthroughTranslator(ModelRef.DIALECT_OPENAI_COMPAT)
        def events = []
        def signal = CancellationSignal.create()

        when: "收到首增量后置取消（等看门狗关闭流唤醒读侧）"
        Thread.ofVirtual().start(() -> {
            def deadline = System.nanoTime() + 5_000_000_000L
            while (events.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            signal.cancel()
        })
        translator.translate(new TranslateContext(
                requestOf(modelOf(ModelRef.DIALECT_OPENAI_COMPAT, upstream.baseUrl())),
                { events << it } as cn.edu.scut.chatbot.loop.StreamConsumer, signal))

        then:
        events.find { it instanceof ModelStreamEvent.TextDelta && it.text() == "前半增量" } != null
        events.last() instanceof ModelStreamEvent.Failed
        events.last().errorCode() == "llm.aborted"

        cleanup:
        upstream.close()
    }

    def "流中途关闭（EOF 提前、finish_reason 缺失）已推增量保留并以 Completed 兜底收尾"() {
        given:
        def upstream = new ScriptedHttpUpstream()
        upstream.addScript("/chat/completions", [
                new ScriptedHttpUpstream.SseChunks(["data: 部分\n\n"]),
                new ScriptedHttpUpstream.DropConnection()
        ])
        upstream.start()
        def translator = new SsePassthroughTranslator(ModelRef.DIALECT_OPENAI_COMPAT)
        def events = []

        when:
        translator.translate(new TranslateContext(
                requestOf(modelOf(ModelRef.DIALECT_OPENAI_COMPAT, upstream.baseUrl())),
                { events << it } as cn.edu.scut.chatbot.loop.StreamConsumer, CancellationSignal.create()))

        then: "流关闭兜底：已推增量保留（配对自校验通过）、终止事件不抛异常"
        noExceptionThrown()
        events.any { it instanceof ModelStreamEvent.TextDelta && it.text() == "部分" }
        events.last() instanceof ModelStreamEvent.Completed

                cleanup :
        upstream.close()
    }

    def "prepareNextTurn 替换 ModelConfig 后下轮请求按新 dialect/baseUrl/modelId 发出"() {
        given: "openai 翻译器首轮产出工具调用；gemini 翻译器次轮产出自然结束——走真网关两轮"
        def oldModel = modelOf(ModelRef.DIALECT_OPENAI_COMPAT, "http://old.example/v4")
        def newModel = new ModelRef(ModelRef.DIALECT_GEMINI, "gemini", "gemini-2.0",
                "http://new.example", new ModelCapabilities(true, true, 1000000, 8192), null, "chat_main")

        def openaiSide = new RecordingTranslator(ModelRef.DIALECT_OPENAI_COMPAT) {
            @Override
            void translate(TranslateContext context) {
                calls.incrementAndGet()
                seenModels << context.request().model()
                context.consumer().onEvent(new ModelStreamEvent.ToolCallStart(0, "call-1", "stubTool"))
                context.consumer().onEvent(new ModelStreamEvent.ToolCallDelta(0, '{"q":"x"}'))
                context.consumer().onEvent(new ModelStreamEvent.ToolCallEnd(0))
                context.consumer().onEvent(new ModelStreamEvent.Completed(new AssistantMessage(
                        "round1", java.time.Instant.now(), 60, null, "",
                        [new AssistantMessage.ToolCallSegment("call-1", "stubTool", '{"q":"x"}')],
                        AssistantMessage.STOP_REASON_TOOL_USE, AssistantMessage.TokenUsage.ZERO)))
            }
        }
        def geminiSide = new RecordingTranslator(ModelRef.DIALECT_GEMINI) {
            @Override
            void translate(TranslateContext context) {
                calls.incrementAndGet()
                seenModels << context.request().model()
                context.consumer().onEvent(new ModelStreamEvent.Completed(new AssistantMessage(
                        "round2", java.time.Instant.now(), 60, null, "第二轮回答", [],
                        AssistantMessage.STOP_REASON_STOP, AssistantMessage.TokenUsage.ZERO)))
            }
        }
        def gateway = new GatewayStreamFn(new DialectRegistry([openaiSide, geminiSide]), noopPool(),
                Mock(SlotResolver), Mock(CostRecorder))

        def clock = new FixedClock()
        def idSeq = new AtomicLong(5000)
        def context = new AgentContext("系统提示词", [], [], new ModelConfig(oldModel, GenerationParams.defaults()))

        def spec = new AgentLoop.RunSpec(
                9100L, "qq", "10000", "80001", 7001L, null, context, RunConfig.turnDefaults(),
                LoopHooks.builder(new TranscriptProjector())
                        .prepareNextTurn((original, config, completedTurns) ->
                                new AgentContext(original.systemPrompt(), original.messages(),
                                        original.tools(), new ModelConfig(newModel, original.params())))
                        .build(),
                ["stubTool": new StubTool("stubTool")], gateway, clock,
                { -> idSeq.incrementAndGet() }, null, null)

        when:
        def outcome = new AgentLoop().execute(spec)

        then: "首轮按 openai-compat 方言旧 baseUrl；替换 ModelConfig 后次轮按 gemini 方言、新 baseUrl/modelId 派发"
        outcome.stopReason() == AgentLoop.RunOutcome.STOP_NATURAL
        openaiSide.seenModels.size() == 1
        openaiSide.seenModels.first().dialect() == ModelRef.DIALECT_OPENAI_COMPAT
        openaiSide.seenModels.first().baseUrl() == "http://old.example/v4"
        geminiSide.seenModels.size() == 1
        geminiSide.seenModels.first().dialect() == ModelRef.DIALECT_GEMINI
        geminiSide.seenModels.first().modelId() == "gemini-2.0"
        geminiSide.seenModels.first().baseUrl() == "http://new.example"
    }
}
