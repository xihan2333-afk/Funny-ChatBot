// 守护纪律：方言差异逐项——四家共用一个翻译器、接收侧多字段探测抹平差异、
// 思考增量推出但消息不含思考段、用量双字段名探测（宪法第七条必测清单；
// contracts/dialect-translators.md 第 1~4 节；报文片段取自四家公开文档示例）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.bootstrap.IdGenerator
import cn.edu.scut.chatbot.bootstrap.JsonMappers
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder
import cn.edu.scut.chatbot.llm.dialect.openai.OpenAiCompatTranslator
import cn.edu.scut.chatbot.llm.gateway.StoredMediaFetcher
import cn.edu.scut.chatbot.llm.gateway.StreamExchange
import cn.edu.scut.chatbot.llm.gateway.TranslateContext
import cn.edu.scut.chatbot.loop.*
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import spock.lang.Specification

class OpenAiCompatTranslatorTest extends Specification {

    def paramService = Mock(SystemParamService) {
        getLong(_, _) >> 300L
        getInt(_, _) >> 300
    }
    def holder = new HttpClientHolder(paramService)
    def exchange = new StreamExchange(holder)
    def clock = new FixedClock()
    def idGen = new IdGenerator(1, clock)
    def events = []

    /** 假媒体取字节：图片装载断言的观察面 */
    def mediaBytes = new StoredMediaFetcher() {
        StoredMediaFetcher.MediaBytes fetch(String storedRef) {
            return new StoredMediaFetcher.MediaBytes([0x89, 0x50, 0x4E, 0x47] as byte[], "image/png")
        }
    }

    def translator = new OpenAiCompatTranslator(exchange, mediaBytes, clock, idGen)

    def fixture(String name) {
        return getClass().getResourceAsStream('/llm-fixtures/' + name).getText("UTF-8")
    }

    /** 报文片段按空行切块逐块 flush（保持上游分片语义） */
    def sseChunksOf(String raw) {
        def blocks = raw.trim().split(/\n\s*\n/).toList()
        return blocks.collect { it + "\n\n" }
    }

    def modelRef = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "test-model",
            null, new ModelCapabilities(true, true, 128000, 4096), null, "chat_main")

    /** 本用例创建的全部假上游（cleanup 统一关闭，防 HttpServer 线程跨用例泄漏） */
    def servers = []

    def translate(ModelRequest request, String sseRaw, ScriptedHttpUpstream upstream = null) {
        events.clear()
        def server = upstream ?: new ScriptedHttpUpstream()
        if (upstream == null) {
            server.addScript("/chat/completions",
                    [new ScriptedHttpUpstream.SseChunks(sseChunksOf(sseRaw))])
        }
        server.start()
        servers << server
        def effective = new ModelRequest(request.model(), request.messages(),
                request.systemPrompt(), request.toolDeclarations(), request.params(),
                request.resolvedCredential())
        // baseUrl 指向假上游（复用请求其余要素）
        def withUrl = new ModelRequest(
                new ModelRef(effective.model().dialect(), effective.model().providerCode(),
                        effective.model().modelId(), server.baseUrl(),
                        effective.model().capabilities(), effective.model().prices(),
                        effective.model().slotCode()),
                effective.messages(), effective.systemPrompt(), effective.toolDeclarations(),
                effective.params(), effective.resolvedCredential())
        translator.translate(new TranslateContext(withUrl,
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        return server
    }

    def simpleRequest(Map overrides = [:]) {
        def messages = overrides.messages ?: [cn.edu.scut.chatbot.loop.LlmMessage.user("大家好")]
        def tools = overrides.tools ?: []
        def params = overrides.params ?: GenerationParams.defaults()
        return new ModelRequest(modelRef, messages, "你是群聊伙伴", tools, params, "sk-test-123")
    }

    def cleanup() {
        servers.each { it.close() }
        servers.clear()
    }

    def "智谱思考流：Thinking 三段在先、Text 三段在后、Completed 消息不含思考段"() {
        when:
        translate(simpleRequest(), fixture("zai-reasoning-stream.sse.txt"))

        then: "块序：思考三段 → 文本三段 → Completed"
        events.count { it instanceof ModelStreamEvent.ThinkingStart } == 1
        events.count { it instanceof ModelStreamEvent.ThinkingDelta } == 2
        events.count { it instanceof ModelStreamEvent.ThinkingEnd } == 1
        events.count { it instanceof ModelStreamEvent.TextStart } == 1
        events.count { it instanceof ModelStreamEvent.TextDelta } == 2
        events.count { it instanceof ModelStreamEvent.TextEnd } == 1

        def completed = events.find { it instanceof ModelStreamEvent.Completed }
        completed != null
        completed.message().textContent() == "大家好呀！"

        and: "负向断言：思考内容不进 AssistantMessage"
        !completed.message().textContent().contains("用户在群里打招呼")
        !completed.message().textContent().contains("简短回应")

        and: "用量齐备（智谱无 cached 字段 → 0）"
        completed.message().usage().inputTokens() == 48
        completed.message().usage().outputTokens() == 12
        completed.message().usage().cachedTokens() == 0
        completed.message().usage().totalTokens() == 60
        completed.message().stopReason() == AssistantMessage.STOP_REASON_STOP
    }

    def "DeepSeek 缓存字段：prompt_cache_hit_tokens 归一为 cachedTokens"() {
        when:
        translate(simpleRequest(), fixture("deepseek-cache-hit-stream.sse.txt"))

        then:
        def completed = events.find { it instanceof ModelStreamEvent.Completed }
        completed.message().usage().inputTokens() == 1200
        completed.message().usage().cachedTokens() == 800
        completed.message().usage().outputTokens() == 18
        completed.message().usage().totalTokens() == 1218
    }

    def "OpenAI 无思考流：无 Thinking 事件、prompt_tokens_details.cached_tokens 归一"() {
        when:
        translate(simpleRequest(), fixture("openai-plain-stream.sse.txt"))

        then:
        events.every { !(it instanceof ModelStreamEvent.ThinkingStart) }
        def completed = events.find { it instanceof ModelStreamEvent.Completed }
        completed.message().usage().cachedTokens() == 0
        completed.message().usage().inputTokens() == 90
        completed.message().textContent() == "好的，没问题。"
    }

    def "豆包混合流：思考→文本→工具→文本交错块序天然保留、finish_reason=tool_calls 闭合全部块"() {
        when:
        translate(simpleRequest(), fixture("doubao-mixed-stream.sse.txt"))

        then: "事件类型序：思考三段 → 文本三段 → 工具三段 → 文本三段 → Completed"
        def kinds = events.collect { it.class.simpleName }.findAll { it != "ThinkingDelta" && it != "TextDelta" && it != "ToolCallDelta" }
        kinds == ["ThinkingStart", "ThinkingEnd", "TextStart", "TextEnd",
                  "ToolCallStart", "ToolCallEnd", "TextStart", "TextEnd", "Completed"]

        def completed = events.find { it instanceof ModelStreamEvent.Completed }
        completed.message().stopReason() == AssistantMessage.STOP_REASON_TOOL_USE

        and: "文本两段拼接完整（交错后文本块重新开块）"
        completed.message().textContent() == "我先查天气。稍等。拿到结果就告诉你。"

        and: "工具调用段参数完整（分片累积）"
        completed.message().toolCalls().size() == 1
        completed.message().toolCalls()[0].name() == "getWeather"
        completed.message().toolCalls()[0].argumentsJson() == '{"city":"广州"}'

        and: "豆包 cached 形态（prompt_tokens_details.cached_tokens）"
        completed.message().usage().cachedTokens() == 120
    }

    def "并行 index 交错分片各自累积、ToolCallEnd 按 index 配对"() {
        given: "两个工具调用的分片交错到达"
        def raw = """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-a","type":"function","function":{"name":"toolA","arguments":"{\\"a\\":"}}]}}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call-b","type":"function","function":{"name":"toolB","arguments":"{\\"b\\":"}}]}}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]}}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"2}"}}]}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]

"""
        when:
        translate(simpleRequest(), raw)

        then:
        def completed = events.find { it instanceof ModelStreamEvent.Completed }
        completed.message().toolCalls().size() == 2
        completed.message().toolCalls()[0].callId() == "call-a"
        completed.message().toolCalls()[0].argumentsJson() == '{"a":1}'
        completed.message().toolCalls()[1].callId() == "call-b"
        completed.message().toolCalls()[1].argumentsJson() == '{"b":2}'
        completed.message().stopReason() == AssistantMessage.STOP_REASON_TOOL_USE
        events.count { it instanceof ModelStreamEvent.ToolCallStart } == 2
        events.count { it instanceof ModelStreamEvent.ToolCallEnd } == 2
    }

    def "请求组装：system 首条、Bearer 鉴权、URL 拼接、工具声明、response_format、passthrough 透传"() {
        given:
        def params = new GenerationParams(0.3, 1024, true,
                ["thinking": '{"type":"disabled"}', "custom_flag": '"yes"'])
        def tools = ['{"name":"getTime","description":"取时间","parameters":{"type":"object","properties":{}}}']
        def upstream = new ScriptedHttpUpstream()
        upstream.addScript("/chat/completions",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(fixture("openai-plain-stream.sse.txt")))])
        upstream.start()

        when:
        def server = translate(simpleRequest([params: params, tools: tools]),
                fixture("openai-plain-stream.sse.txt"), upstream)
        def captured = server.requestsFor("/chat/completions")[0]
        def body = JsonMappers.shared().readTree(captured.body())

        then:
        captured.headers().getFirst("Authorization") == "Bearer sk-test-123"
        body.path("model").asText() == "test-model"
        body.path("messages").get(0).path("role").asText() == "system"
        body.path("messages").get(0).path("content").asText() == "你是群聊伙伴"
        body.path("messages").get(1).path("role").asText() == "user"
        body.path("temperature").asDouble() == 0.3
        body.path("max_tokens").asInt() == 1024
        body.path("response_format").path("type").asText() == "json_object"
        body.path("tools").get(0).path("type").asText() == "function"
        body.path("tools").get(0).path("function").path("name").asText() == "getTime"
        body.path("tools").get(0).path("function").path("parameters").path("type").asText() == "object"
        body.path("thinking").path("type").asText() == "disabled"
        body.path("custom_flag").asText() == "yes"

        cleanup:
        server?.close()
    }

    def "携带 images 的 user 消息装载 image_url data URI"() {
        given:
        def imageMessage = new cn.edu.scut.chatbot.loop.LlmMessage(
                cn.edu.scut.chatbot.loop.LlmMessage.ROLE_USER, "看看这张图", [], null, false,
                [new cn.edu.scut.chatbot.loop.message.ImageContent("media-001")])
        def upstream = new ScriptedHttpUpstream()
        upstream.addScript("/chat/completions",
                [new ScriptedHttpUpstream.SseChunks(sseChunksOf(fixture("openai-plain-stream.sse.txt")))])
        upstream.start()

        when:
        def server = translate(simpleRequest([messages: [imageMessage]]),
                fixture("openai-plain-stream.sse.txt"), upstream)
        def body = JsonMappers.shared().readTree(
                server.requestsFor("/chat/completions")[0].body())

        then:
        def content = body.path("messages").get(1).path("content")
        content.isArray()
        content.get(0).path("type").asText() == "text"
        content.get(0).path("text").asText() == "看看这张图"
        content.get(1).path("type").asText() == "image_url"
        content.get(1).path("image_url").path("url").asText().startsWith("data:image/png;base64,")

        cleanup:
        server?.close()
    }

    def "非法 JSON 分片丢弃记告警流继续（单分片损坏不杀整流）"() {
        given:
        def raw = """data: {broken json !!!

data: {"choices":[{"index":0,"delta":{"content":"正常内容"}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

"""
        when:
        translate(simpleRequest(), raw)

        then:
        def completed = events.find { it instanceof ModelStreamEvent.Completed }
        completed != null
        completed.message().textContent() == "正常内容"
    }

    def "上游 429 错误体归一 rate_limited（反馈联动经网关覆盖于 ErrorNormalizationTest）"() {
        given:
        def upstream = new ScriptedHttpUpstream()
        upstream.addScript("/chat/completions", [new ScriptedHttpUpstream.Respond(429,
                "application/json", '{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}')])
        upstream.start()
        def request = new ModelRequest(
                new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "m", upstream.baseUrl(),
                        new ModelCapabilities(true, true, 128000, 4096), null, "chat_main"),
                [], "s", [], GenerationParams.defaults(), "sk-limited")

        when:
        translator.translate(new TranslateContext(request,
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))

        then:
        events.size() == 1
        events[0] instanceof ModelStreamEvent.Failed
                events[0].errorCode() == "llm.rate_limited"

        cleanup:
        upstream.close()
    }
}
