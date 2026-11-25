// 守护纪律：openai-compat 块边界合成的流关闭兜底——finish_reason 与 [DONE] 哨兵双缺失
// 时仍闭合全部打开块并推 Completed，配对自校验通过（TextStart/TextEnd、
// ToolCallStart/ToolCallEnd 恒配对；contracts/dialect-translators.md 第 2 节
// 「流关闭时 finish_reason 缺失 → 闭合全部打开块并推 Completed」）。
// 既有 GatewayStreamTest 以测试翻译器验证该兜底，本类用真 FlowAssembler 刺激——
// 方言无显式块标记、隐式结构显式化的收尾纪律必须落在生产装配器上。
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
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import spock.lang.Specification

class StreamCloseFallbackTest extends Specification {

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

    /** 分片后直接耗尽剧本——上游关闭响应体，客户端读到 EOF（无 finish_reason、无 [DONE]） */
    def streamClosingAfter(List<String> chunks) {
        events.clear()
        def server = new ScriptedHttpUpstream()
        server.addScript("/chat/completions", [new ScriptedHttpUpstream.SseChunks(chunks)])
        server.start()
        servers << server
        def request = new ModelRequest(
                new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "test-model", server.baseUrl(),
                        new ModelCapabilities(true, true, 128000, 4096), null, "chat_main"),
                [LlmMessage.user("你好")], "系统提示", [], GenerationParams.defaults(), "sk-test")
        translator.translate(new TranslateContext(request,
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

    def "文本块中途流关闭：TextEnd 兜底闭合、Completed 完整交付正文（配对自校验通过）"() {
        when: "两个 content 分片后上游直接关闭——finish_reason 未到、[DONE] 未发"
        streamClosingAfter([
                'data: {"choices":[{"index":0,"delta":{"content":"前半"}}]}\n\n',
                'data: {"choices":[{"index":0,"delta":{"content":"后半"}}]}\n\n',
        ])

        then:
        noExceptionThrown()
        events.count { it instanceof ModelStreamEvent.TextStart } == 1
        events.count { it instanceof ModelStreamEvent.TextDelta } == 2
        events.count { it instanceof ModelStreamEvent.TextEnd } == 1
        def terminal = completed()
        terminal != null
        terminal.message().textContent() == "前半后半"
        terminal.message().stopReason() == AssistantMessage.STOP_REASON_STOP
    }

    def "工具调用中途流关闭：ToolCallEnd 兜底闭合、参数累积完整进 Completed"() {
        when: "工具调用参数分了两个分片、无 finish_reason 无 [DONE] 即流关闭"
        streamClosingAfter([
                'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1",'
                        + '"type":"function","function":{"name":"getWeather","arguments":"{\\"ci"}}]}}]}\n\n',
                'data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,'
                        + '"function":{"arguments":"ty\\":\\"广州\\"}"}}]}}]}\n\n',
        ])

        then:
        events.count { it instanceof ModelStreamEvent.ToolCallStart } == 1
        events.count { it instanceof ModelStreamEvent.ToolCallDelta } == 2
        events.count { it instanceof ModelStreamEvent.ToolCallEnd } == 1
        def terminal = completed()
        terminal != null
        terminal.message().toolCalls().size() == 1
        terminal.message().toolCalls()[0].callId() == "call-1"
        terminal.message().toolCalls()[0].argumentsJson() == '{"city":"广州"}'
    }
}
