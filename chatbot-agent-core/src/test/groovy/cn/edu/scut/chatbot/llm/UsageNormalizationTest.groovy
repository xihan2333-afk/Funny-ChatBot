// 守护纪律：用量归一五值全口径——reasoning_tokens 探测与缺省零、total_tokens 缺失
// 兜底 input+output、上游不返回用量块 usage 落 null 不阻塞 Completed
// （宪法第七条必测清单「用量归一」；contracts/dialect-translators.md 第 4 节）。
// 既有 OpenAiCompatTranslatorTest 回放的四家片段全部携带 total_tokens 且无思考口径
// 用量，本类补齐其余探测分支——五值中的每一值都要有断言对应。
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.bootstrap.IdGenerator
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder
import cn.edu.scut.chatbot.llm.dialect.openai.OpenAiCompatTranslator
import cn.edu.scut.chatbot.llm.gateway.StoredMediaFetcher
import cn.edu.scut.chatbot.llm.gateway.StreamExchange
import cn.edu.scut.chatbot.llm.gateway.TranslateContext
import cn.edu.scut.chatbot.loop.*
import spock.lang.Specification

class UsageNormalizationTest extends Specification {

    def paramService = Mock(SystemParamService) {
        getLong(_, _) >> 300L
        getInt(_, _) >> 300
    }
    def holder = new HttpClientHolder(paramService)
    def exchange = new StreamExchange(holder)
    def clock = new FixedClock()
    def idGen = new IdGenerator(1, clock)
    def mediaFetcher = (StoredMediaFetcher) { ref -> null }
    def translator = new OpenAiCompatTranslator(exchange, mediaFetcher, clock, idGen)

    def events = []
    def servers = []

    def modelRef = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "test-model",
            null, new ModelCapabilities(true, true, 128000, 4096), null, "chat_main")

    /** 单个 usage 事件的完整小流（正文一 delta + finish_reason + 用量块 + [DONE]） */
    def streamOf(String usageJson) {
        return """data: {"choices":[{"index":0,"delta":{"content":"回答"}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],${usageJson}}

data: [DONE]

"""
    }

    def stream(String sseRaw) {
        events.clear()
        def server = new ScriptedHttpUpstream()
        server.addScript("/chat/completions",
                [new ScriptedHttpUpstream.SseChunks(sseRaw.trim().split(/\n\s*\n/).collect { it + "\n\n" })])
        server.start()
        servers << server
        def withUrl = new ModelRequest(
                new ModelRef(modelRef.dialect(), modelRef.providerCode(), modelRef.modelId(),
                        server.baseUrl(), modelRef.capabilities(), modelRef.prices(), modelRef.slotCode()),
                [LlmMessage.user("你好")], "系统提示", [], GenerationParams.defaults(), "sk-test")
        translator.translate(new TranslateContext(withUrl,
                { event -> events << event } as cn.edu.scut.chatbot.loop.StreamConsumer,
                CancellationSignal.create()))
        return server
    }

    def completed() {
        return events.find { it instanceof ModelStreamEvent.Completed }
    }

    def cleanup() {
        servers.each { it.close() }
        servers.clear()
    }

    def "reasoning_tokens 归一：completion_tokens_details.reasoning_tokens 非零计入、含思考口径原样不折算"() {
        when: "用量块携带 reasoning_tokens=35（思考模型把思考 token 并进 completion 的口径）"
        stream(streamOf('"usage":{"prompt_tokens":100,"completion_tokens":50,'
                + '"completion_tokens_details":{"reasoning_tokens":35},"total_tokens":150}'))

        then:
        def usage = completed().message().usage()
        usage.reasoningTokens() == 35
        usage.inputTokens() == 100
        usage.outputTokens() == 50
        usage.totalTokens() == 150
    }

    def "completion_tokens_details 缺失：reasoningTokens 落零（无思考口径不虚构数值）"() {
        when:
        stream(streamOf('"usage":{"prompt_tokens":48,"completion_tokens":12,"total_tokens":60}'))

        then:
        completed().message().usage().reasoningTokens() == 0
    }

    def "total_tokens 缺失兜底 input+output（上游只回 prompt/completion 两值的形态）"() {
        when:
        stream(streamOf('"usage":{"prompt_tokens":1200,"completion_tokens":18}'))

        then:
        def usage = completed().message().usage()
        usage.totalTokens() == 1218
        usage.totalTokens() == usage.inputTokens() + usage.outputTokens()
    }

    def "上游不返回用量块：Completed 照常交付、usage 落 null 不阻塞"() {
        when: "全程无 usage 字段——上游省略用量块的形态"
        stream('''data: {"choices":[{"index":0,"delta":{"content":"没有用量的回答"}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]

''')

        then:
        completed() != null
        completed().message().textContent() == "没有用量的回答"
        completed().message().usage() == null
    }
}
