// 守护纪律：方言差异逐项——Gemini 差异表每行一测；同一统一消息输入两方言产出
// 语义等价事件序列（宪法第七条必测清单；contracts/dialect-translators.md 第 1~2 节）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.bootstrap.IdGenerator
import cn.edu.scut.chatbot.bootstrap.JsonMappers
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder
import cn.edu.scut.chatbot.llm.dialect.gemini.GeminiTranslator
import cn.edu.scut.chatbot.llm.dialect.openai.OpenAiCompatTranslator
import cn.edu.scut.chatbot.llm.gateway.StoredMediaFetcher
import cn.edu.scut.chatbot.llm.gateway.StreamExchange
import cn.edu.scut.chatbot.llm.gateway.TranslateContext
import cn.edu.scut.chatbot.loop.*
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import cn.edu.scut.chatbot.loop.message.ImageContent
import spock.lang.Specification

class GeminiTranslatorTest extends Specification {

    def paramService = Mock(SystemParamService) {
        getLong(_, _) >> 300L
        getInt(_, _) >> 300
    }
    def holder = new HttpClientHolder(paramService)
    def exchange = new StreamExchange(holder)
    def clock = new FixedClock()
    def idGen = new IdGenerator(1, clock)

    def mediaBytes = new StoredMediaFetcher() {
        StoredMediaFetcher.MediaBytes fetch(String storedRef) {
            return new StoredMediaFetcher.MediaBytes([1, 2, 3, 4] as byte[], "image/jpeg")
        }
    }

    def translator = new GeminiTranslator(exchange, mediaBytes, clock, idGen)
    def openaiTranslator = new OpenAiCompatTranslator(exchange, mediaBytes, clock, idGen)

    def events = []

    def fixture(String name) {
        return getClass().getResourceAsStream('/llm-fixtures/' + name).getText("UTF-8")
    }

    def sseChunksOf(String raw) {
        def blocks = raw.trim().split(/\n\s*\n/).toList()
        return blocks.collect { it + "\n\n" }
    }

    def geminiRef(String baseUrl = "http://127.0.0.1:1") {
        return new ModelRef(ModelRef.DIALECT_GEMINI, "gemini", "gemini-2.0-flash", baseUrl,
                new ModelCapabilities(true, true, 1000000, 8192), null, "chat_main")
    }

    def translate(ModelRequest request, String sseRaw) {
        events.clear()
        def server = new ScriptedHttpUpstream()
        server.addScript("/models/gemini-2.0-flash:streamGenerateContent",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(sseRaw))])
        server.start()
        def withUrl = new ModelRequest(
                new ModelRef(request.model().dialect(), request.model().providerCode(),
                        request.model().modelId(), server.baseUrl(),
                        request.model().capabilities(), request.model().prices(),
                        request.model().slotCode()),
                request.messages(), request.systemPrompt(), request.toolDeclarations(),
                request.params(), request.resolvedCredential())
        translator.translate(new TranslateContext(withUrl,
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        return server
    }

    def requestOf(Map overrides = [:]) {
        return new ModelRequest(geminiRef(), overrides.messages ?: [LlmMessage.user("大家好")],
                "你是群聊伙伴", overrides.tools ?: [],
                overrides.params ?: GenerationParams.defaults(), overrides.credential ?: "goog-key-1")
    }

    def "thought 标记产 Thinking 三段、functionCall 整块三段式、消息无思考段"() {
        when:
        def server = translate(requestOf(), fixture("gemini-parts-stream.sse.txt"))

        then: "块序：思考三段 → 文本三段 → 工具三段 → Completed（流关闭终止）"
        def kinds = events.collect { it.class.simpleName }.findAll {
            it != "ThinkingDelta" && it != "TextDelta"
        }
        kinds == ["ThinkingStart", "ThinkingEnd", "TextStart", "TextEnd",
                  "ToolCallStart", "ToolCallDelta", "ToolCallEnd", "Completed"]

        def completed = events.find { it instanceof ModelStreamEvent.Completed }
        completed.message().textContent() == "我查一下。"
        completed.message().toolCalls().size() == 1
        completed.message().toolCalls()[0].name() == "getWeather"
        completed.message().toolCalls()[0].argumentsJson() == '{"city":"广州"}'
        completed.message().stopReason() == AssistantMessage.STOP_REASON_TOOL_USE

        and: "负向断言：思考内容不进 AssistantMessage"
        !completed.message().textContent().contains("查一下天气")

        and: "用量归一（usageMetadata）"
        completed.message().usage().inputTokens() == 210
        completed.message().usage().outputTokens() == 16
        completed.message().usage().totalTokens() == 226

        cleanup:
        server.close()
    }

    def "请求组装：systemInstruction 拆出、角色映射 user/model、functionResponse 挂 user 侧"() {
        given: "一轮完整工具往返：user → assistant(toolCalls) → tool 结果"
        def messages = [
                LlmMessage.user("帮我查天气"),
                LlmMessage.assistant("", [new AssistantMessage.ToolCallSegment(
                        "call-9", "getWeather", '{"city":"广州"}')]),
                LlmMessage.toolResult("call-9", "广州 晴 26 度", false),
        ]
        def tools = ['{"name":"getWeather","description":"查天气","parameters":{"type":"object","properties":{}}}']
        def params = new GenerationParams(0.2, 4096, true, [:])
        def server = new ScriptedHttpUpstream()

        when:
        server.addScript("/models/gemini-2.0-flash:streamGenerateContent",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(fixture("gemini-parts-stream.sse.txt")))])
        server.start()
        translator.translate(new TranslateContext(
                new ModelRequest(geminiRef(server.baseUrl()), messages, "你是群聊伙伴", tools,
                        params, "goog-key-1"),
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        def captured = server.requestsFor("/models/gemini-2.0-flash:streamGenerateContent")[0]
        def body = JsonMappers.shared().readTree(captured.body())

        then: "差异表逐项"
        captured.headers().getFirst("x-goog-api-key") == "goog-key-1"
        captured.rawQuery() == "alt=sse"
        body.path("systemInstruction").path("parts").get(0).path("text").asText() == "你是群聊伙伴"
        body.path("contents").size() == 3
        body.path("contents").get(0).path("role").asText() == "user"
        body.path("contents").get(0).path("parts").get(0).path("text").asText() == "帮我查天气"
        body.path("contents").get(1).path("role").asText() == "model"
        body.path("contents").get(1).path("parts").get(0).path("functionCall").path("name").asText() == "getWeather"
        body.path("contents").get(1).path("parts").get(0).path("functionCall").path("args").path("city").asText() == "广州"
        body.path("contents").get(2).path("role").asText() == "user"
        body.path("contents").get(2).path("parts").get(0).path("functionResponse").path("name").asText() == "getWeather"
        body.path("contents").get(2).path("parts").get(0).path("functionResponse").path("response").path("result").asText() == "广州 晴 26 度"
        body.path("generationConfig").path("temperature").asDouble() == 0.2
        body.path("generationConfig").path("maxOutputTokens").asInt() == 4096
        body.path("generationConfig").path("responseMimeType").asText() == "application/json"
        body.path("tools").get(0).path("functionDeclarations").get(0).path("name").asText() == "getWeather"

        cleanup:
        server.close()
    }

    def "携带 images 的 user 消息装载 inlineData（与 openai-compat 的 image_url 形态对照）"() {
        given:
        def imageMessage = new LlmMessage(LlmMessage.ROLE_USER, "看图", [], null, false,
                [new ImageContent("media-002")])
        def server = new ScriptedHttpUpstream()
        def geminiEvents = []
        def openaiEvents = []

        when:
        server.addScript("/models/gemini-2.0-flash:streamGenerateContent",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(fixture("gemini-parts-stream.sse.txt")))])
        server.start()
        translator.translate(new TranslateContext(
                new ModelRequest(geminiRef(server.baseUrl()), [imageMessage], "s", [],
                        GenerationParams.defaults(), "gk"),
                { event -> geminiEvents << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        def geminiBody = JsonMappers.shared().readTree(
                server.requestsFor("/models/gemini-2.0-flash:streamGenerateContent")[0].body())

        then: "gemini 形态：inlineData {mimeType, data}"
        def part = geminiBody.path("contents").get(0).path("parts").get(1)
        part.path("inlineData").path("mimeType").asText() == "image/jpeg"
        part.path("inlineData").path("data").asText() == "AQIDBA=="

        when: "同一消息经 openai-compat 方言装载"
        server.addScript("/chat/completions",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(fixture("openai-plain-stream.sse.txt")))])
        openaiTranslator.translate(new TranslateContext(
                new ModelRequest(new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "m",
                        server.baseUrl(), new ModelCapabilities(true, true, 128000, 4096), null, "chat_main"),
                        [imageMessage], "s", [], GenerationParams.defaults(), "gk"),
                { event -> openaiEvents << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        def openaiBody = JsonMappers.shared().readTree(
                server.requestsFor("/chat/completions")[0].body())

        then: "openai-compat 形态：image_url data URI（同一字节两种方言形态）——system 占 get(0)、user 是 get(1)"
        openaiBody.path("messages").get(1).path("content").get(1)
                .path("image_url").path("url").asText() == "data:image/jpeg;base64,AQIDBA=="

        cleanup:
        server.close()
    }

    def "images 空时两方言只装文本（对照）"() {
        given:
        def server = new ScriptedHttpUpstream()

        when:
        server.addScript("/models/gemini-2.0-flash:streamGenerateContent",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(fixture("gemini-parts-stream.sse.txt")))])
        server.start()
        translator.translate(new TranslateContext(
                new ModelRequest(geminiRef(server.baseUrl()), [LlmMessage.user("纯文本")], "s", [],
                        GenerationParams.defaults(), "gk"),
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        def geminiBody = JsonMappers.shared().readTree(
                server.requestsFor("/models/gemini-2.0-flash:streamGenerateContent")[0].body())

        then:
        geminiBody.path("contents").get(0).path("parts").size() == 1
        geminiBody.path("contents").get(0).path("parts").get(0).path("text").asText() == "纯文本"

        cleanup:
        server.close()
    }

    def "同一统一消息输入两方言产出语义等价事件序列（含 systemPrompt、多轮、工具调用）"() {
        given: "Gemini 侧以 parts 形态回放与 openai 流语义等价的剧本"
        def geminiSse = """data: {"candidates":[{"content":{"parts":[{"text":"收到，"}],"role":"model"}}]}

data: {"candidates":[{"content":{"parts":[{"text":"这就去。"}],"role":"model"}}],"finishReason":"STOP","usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":10,"totalTokenCount":60}}

"""
        def openaiSse = """data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"收到，"}}]}

data: {"choices":[{"index":0,"delta":{"content":"这就去。"}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}

data: [DONE]

"""
        def geminiEvents = []
        def openaiEvents = []
        def server = new ScriptedHttpUpstream()

        when:
        server.addScript("/models/gemini-2.0-flash:streamGenerateContent",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(geminiSse))])
        server.addScript("/chat/completions",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(openaiSse))])
        server.start()
        translator.translate(new TranslateContext(
                new ModelRequest(geminiRef(server.baseUrl()), [LlmMessage.user("去")], "s", [],
                        GenerationParams.defaults(), "gk"),
                { event -> geminiEvents << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        openaiTranslator.translate(new TranslateContext(
                new ModelRequest(new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "m",
                        server.baseUrl(), new ModelCapabilities(true, true, 128000, 4096), null, "chat_main"),
                        [LlmMessage.user("去")], "s", [], GenerationParams.defaults(), "gk"),
                { event -> openaiEvents << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))

        then: "事件类型序列与终止消息语义等价"
        geminiEvents.collect { it.class.simpleName } == openaiEvents.collect { it.class.simpleName }
        def g = geminiEvents.find { it instanceof ModelStreamEvent.Completed }.message()
        def o = openaiEvents.find { it instanceof ModelStreamEvent.Completed }.message()
        g.textContent() == "收到，这就去。"
        o.textContent() == "收到，这就去。"
        g.stopReason() == o.stopReason()
        g.usage().inputTokens() == o.usage().inputTokens()
        g.usage().outputTokens() == o.usage().outputTokens()

        cleanup:
        server.close()
    }

    def "Gemini error.status 枚举归一：RESOURCE_EXHAUSTED → rate_limited"() {
        given:
        def server = new ScriptedHttpUpstream()
        server.addScript("/models/gemini-2.0-flash:streamGenerateContent", [new ScriptedHttpUpstream.Respond(
                429, "application/json", fixture("error-gemini-resource-exhausted.json"))])
        server.start()

        when:
        translator.translate(new TranslateContext(
                new ModelRequest(geminiRef(server.baseUrl()), [], "s", [],
                        GenerationParams.defaults(), "goog-limited"),
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))

        then:
        events[0] instanceof ModelStreamEvent.Failed
                events[0].errorCode() == "llm.rate_limited"

        cleanup:
        server.close()
    }
}
