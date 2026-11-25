// 守护纪律：方言差异逐项——发送侧映射表每一行都应有断言对应（宪法第七条必测清单；
// contracts/dialect-translators.md 第 1 节）。Gemini 侧工具往返已由 GeminiTranslatorTest
// 覆盖、openai passthrough 已由 OpenAiCompatTranslatorTest 覆盖；本类补齐尚未有测试
// 对应的两行：openai-compat 的 assistant toolCalls → tool_calls[] 与工具结果
// role=tool + tool_call_id + content，以及 gemini passthrough 的 generationConfig.*
// 双落点合并（generationConfig.* 键进 generationConfig、其余顶层原样合并）。
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
import spock.lang.Specification

class SendSideContractTest extends Specification {

    def paramService = Mock(SystemParamService) {
        getLong(_, _) >> 300L
        getInt(_, _) >> 300
    }
    def holder = new HttpClientHolder(paramService)
    def exchange = new StreamExchange(holder)
    def clock = new FixedClock()
    def idGen = new IdGenerator(1, clock)
    def mediaFetcher = (StoredMediaFetcher) { ref -> null }

    def openaiTranslator = new OpenAiCompatTranslator(exchange, mediaFetcher, clock, idGen)
    def geminiTranslator = new GeminiTranslator(exchange, mediaFetcher, clock, idGen)

    def events = []
    def servers = []

    def fixture(String name) {
        return getClass().getResourceAsStream('/llm-fixtures/' + name).getText("UTF-8")
    }

    def sseChunksOf(String raw) {
        return raw.trim().split(/\n\s*\n/).collect { it + "\n\n" }
    }

    /** 组装捕获：回放一段能让交换完整收尾的剧本，取回假上游捕获的请求体 JSON 树（baseUrl 重指向假上游） */
    def capturedBody(String path, String fixtureName, ModelRequest request,
                     cn.edu.scut.chatbot.llm.gateway.DialectTranslator translator) {
        def server = new ScriptedHttpUpstream()
        server.addScript(path, [new ScriptedHttpUpstream.SseChunks(sseChunksOf(fixture(fixtureName)))])
        server.start()
        servers << server
        def origin = request.model()
        def withUrl = new ModelRequest(
                new ModelRef(origin.dialect(), origin.providerCode(), origin.modelId(),
                        server.baseUrl(), origin.capabilities(), origin.prices(), origin.slotCode()),
                request.messages(), request.systemPrompt(), request.toolDeclarations(),
                request.params(), request.resolvedCredential())
        translator.translate(new TranslateContext(withUrl,
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        return JsonMappers.shared().readTree(server.requestsFor(path)[0].body())
    }

    def cleanup() {
        servers.each { it.close() }
        servers.clear()
    }

    def "openai-compat 工具往返映射：assistant toolCalls → tool_calls[]、tool 结果 role=tool + tool_call_id + content"() {
        given: "一轮完整工具往返：user → assistant(带文本与工具调用段) → tool 结果"
        def messages = [
                LlmMessage.user("帮我查天气"),
                LlmMessage.assistant("我先查一下。", [new AssistantMessage.ToolCallSegment(
                        "call-9", "getWeather", '{"city":"广州"}')]),
                LlmMessage.toolResult("call-9", "广州 晴 26 度", false),
        ]
        def request = new ModelRequest(
                new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "test-model", null,
                        new ModelCapabilities(true, true, 128000, 4096), null, "chat_main"),
                messages, "你是群聊伙伴", [], GenerationParams.defaults(), "sk-test")

        when:
        def body = capturedBody("/chat/completions", "openai-plain-stream.sse.txt", request, openaiTranslator)

        then: "assistant 消息：正文 content 与 tool_calls[] 并存（id/type/function.name/arguments 逐项）"
        body.path("messages").get(2).path("role").asText() == "assistant"
        body.path("messages").get(2).path("content").asText() == "我先查一下。"
        body.path("messages").get(2).path("tool_calls").get(0).path("id").asText() == "call-9"
        body.path("messages").get(2).path("tool_calls").get(0).path("type").asText() == "function"
        body.path("messages").get(2).path("tool_calls").get(0).path("function").path("name").asText() == "getWeather"
        body.path("messages").get(2).path("tool_calls").get(0).path("function").path("arguments").asText() == '{"city":"广州"}'

        and: "工具结果消息：role=tool、tool_call_id 关联、content 携带结果正文（system 占 get(0)、user 占 get(1)）"
        body.path("messages").get(3).path("role").asText() == "tool"
        body.path("messages").get(3).path("tool_call_id").asText() == "call-9"
        body.path("messages").get(3).path("content").asText() == "广州 晴 26 度"
    }

    def "gemini passthrough 双落点：generationConfig.* 键进 generationConfig、其余顶层原样合并"() {
        given: "passthrough 同时携带 generationConfig 前缀键与顶层私有键"
        def params = new GenerationParams(0.2, 4096, false, [
                "generationConfig.candidateCount": "2",
                "safetySettings"                 : '[{"category":"HARM_CATEGORY_HARASSMENT"}]',
        ])
        def request = new ModelRequest(
                new ModelRef(ModelRef.DIALECT_GEMINI, "gemini", "gemini-2.0-flash", null,
                        new ModelCapabilities(true, true, 1000000, 8192), null, "chat_main"),
                [LlmMessage.user("你好")], "你是群聊伙伴", [], params, "goog-key-1")

        when:
        def body = capturedBody("/models/gemini-2.0-flash:streamGenerateContent",
                "gemini-parts-stream.sse.txt", request, geminiTranslator)

        then: "generationConfig.* 前缀键进 generationConfig（JSON 解析为数值）"
        body.path("generationConfig").path("candidateCount").asInt() == 2

        and: "其余键顶层原样合并（数组形态不解释），且前缀键不会以字面量名落在顶层"
        body.path("safetySettings").isArray()
        body.path("safetySettings").get(0).path("category").asText() == "HARM_CATEGORY_HARASSMENT"
        body.path("generationConfig.candidateCount").isMissingNode()
    }
}
