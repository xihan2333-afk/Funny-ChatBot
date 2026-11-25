// 守护纪律：八种 LLM 错误码逐项刺激验证归一正确（宪法第七条必测清单）——
// 三重探测 context_overflow 各路独立刺激、错误码与凭证反馈联动（假池断言 kind 与掩码）、
// 连接级只重试一次而应用级错误零重试、ERROR 日志携带 errorCode 与 traceId
// （contracts/dialect-translators.md 第 5 节；contracts/slot-and-credentials.md 第 3 节）
package cn.edu.scut.chatbot.llm

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cn.edu.scut.chatbot.bootstrap.IdGenerator
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder
import cn.edu.scut.chatbot.llm.dialect.gemini.GeminiTranslator
import cn.edu.scut.chatbot.llm.dialect.openai.OpenAiCompatTranslator
import cn.edu.scut.chatbot.llm.gateway.DialectRegistry
import cn.edu.scut.chatbot.llm.gateway.GatewayStreamFn
import cn.edu.scut.chatbot.llm.gateway.StoredMediaFetcher
import cn.edu.scut.chatbot.llm.gateway.StreamExchange
import cn.edu.scut.chatbot.llm.slot.CostRecorder
import cn.edu.scut.chatbot.llm.slot.CredentialPool
import cn.edu.scut.chatbot.llm.slot.SlotResolver
import cn.edu.scut.chatbot.loop.*
import org.slf4j.LoggerFactory
import spock.lang.Specification

class ErrorNormalizationTest extends Specification {

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

    /** 假凭证池：反馈联动的观察面（kind 与掩码断言） */
    def feedbackCalls = []
    def pool = new CredentialPool() {
        @Override
        String nextAvailable(String providerCode) { return null }

        @Override
        void feedback(String providerCode, String usedKeyMask, CredentialPool.FeedbackKind kind) {
            feedbackCalls << [providerCode, usedKeyMask, kind]
        }
    }
    def gateway = new GatewayStreamFn(new DialectRegistry([openaiTranslator, geminiTranslator]), pool,
            Mock(SlotResolver), Mock(CostRecorder))

    def events = []
    def servers = []

    def openaiModel = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "glm-4.6",
            null, new ModelCapabilities(true, true, 128000, 4096), null, "chat_main")
    def geminiModel = new ModelRef(ModelRef.DIALECT_GEMINI, "google", "gemini-2.0-flash",
            null, new ModelCapabilities(true, true, 1000000, 8192), null, "chat_main")

    /** 经网关走真翻译器全链路（baseUrl 重指向假上游） */
    def stream(ModelRef model, String credential, List<ScriptedHttpUpstream.Step> steps) {
        events.clear()
        def server = new ScriptedHttpUpstream()
        String path = ModelRef.DIALECT_GEMINI.equals(model.dialect())
                ? "/models/" + model.modelId() + ":streamGenerateContent" : "/chat/completions"
        server.addScript(path, steps)
        server.start()
        servers << server
        def withUrl = new ModelRef(model.dialect(), model.providerCode(), model.modelId(),
                server.baseUrl(), model.capabilities(), model.prices(), model.slotCode())
        gateway.stream(new ModelRequest(withUrl, [LlmMessage.user("你好")], "系统提示", [],
                GenerationParams.defaults(), credential),
                { event -> events << event } as StreamConsumer, CancellationSignal.create())
        return server
    }

    def cleanup() {
        servers.each { it.close() }
        servers.clear()
    }

    def "429 归一 rate_limited、反馈假池冷却（掩码定位）、应用级零重试"() {
        when:
        def server = stream(openaiModel, "sk-limited-key-1",
                [new ScriptedHttpUpstream.Respond(429, "application/json",
                        '{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}')])

        then:
        events.size() == 1
        events[0] instanceof ModelStreamEvent.Failed
                events[0].errorCode() == "llm.rate_limited"
        events[0].errorMessage().contains("Rate limit reached")
        feedbackCalls == [["zai", "sk-***y-1", CredentialPool.FeedbackKind.RATE_LIMITED]]
        server.totalHits() == 1
    }

    def "401 归一 auth、反馈假池停用"() {
        when:
        stream(openaiModel, "sk-bad-credential",
                [new ScriptedHttpUpstream.Respond(401, "application/json",
                        '{"error":{"message":"Invalid API key","type":"authentication_error"}}')])

        then:
        events[0].errorCode() == "llm.auth"
        feedbackCalls == [["zai", "sk-***ial", CredentialPool.FeedbackKind.AUTH_FAILED]]
    }

    def "长度文案 400 归一 context_overflow（三重探测第一路）"() {
        when:
        stream(openaiModel, "sk-x", [new ScriptedHttpUpstream.Respond(400, "application/json",
                '{"error":{"message":"This model maximum context length is 128000 tokens","type":"invalid_request_error"}}')])

        then:
        events[0].errorCode() == "llm.context_overflow"
        feedbackCalls == []
    }

    def "错误体 prompt_tokens 超窗口归一 context_overflow（三重探测第二路，无长度文案）"() {
        given: "报文不含长度类字样，只带超过窗口的 prompt_tokens"
        def body = '{"error":{"message":"Invalid request","type":"invalid_request_error"},"usage":{"prompt_tokens":200000}}'

        when:
        stream(openaiModel, "sk-x", [new ScriptedHttpUpstream.Respond(400, "application/json", body)])

        then:
        events[0].errorCode() == "llm.context_overflow"
    }

    def "空输出且 finish_reason=length 停止归一 context_overflow（三重探测第三路）"() {
        given: "全程无 content 分片、以 length 停止——长度上限吃光输出的形态"
        def sse = '''data: {"choices":[{"index":0,"delta":{"role":"assistant"}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

data: [DONE]

'''
        when:
        stream(openaiModel, "sk-x", [new ScriptedHttpUpstream.SseChunks([sse])])

        then:
        events.last() instanceof ModelStreamEvent.Failed
        events.last().errorCode() == "llm.context_overflow"

        and: "有正文的 length 停止不是溢出（对照——正文完整交付）"
        def withText = '''data: {"choices":[{"index":0,"delta":{"content":"部分回答"}}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

data: [DONE]

'''
        events.clear()
        stream(openaiModel, "sk-x", [new ScriptedHttpUpstream.SseChunks([withText])])
        events.last() instanceof ModelStreamEvent.Completed
    }

    def "内容策略枚举与文案归一 content_policy 不重试"() {
        when: "openai 阵营文案形态"
        def server = stream(openaiModel, "sk-x", [new ScriptedHttpUpstream.Respond(400, "application/json",
                '{"error":{"message":"Your request was rejected as a result of safety","code":"content_policy_filter"}}')])

        then:
        events[0].errorCode() == "llm.content_policy"
        feedbackCalls == []
        server.totalHits() == 1

        when: "Gemini error.status=SAFETY 枚举形态"
        events.clear()
        stream(geminiModel, "goog-key-1", [new ScriptedHttpUpstream.Respond(400, "application/json",
                '{"error":{"code":400,"message":"Candidate was blocked by safety","status":"SAFETY"}}')])

        then:
        events[0].errorCode() == "llm.content_policy"
    }

    def "Gemini error.status 枚举：RESOURCE_EXHAUSTED 与 UNAUTHENTICATED 各归一"() {
        when:
        stream(geminiModel, "goog-key-1", [new ScriptedHttpUpstream.Respond(429, "application/json",
                '{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}')])

        then:
        events[0].errorCode() == "llm.rate_limited"
        feedbackCalls == [["google", "goo***y-1", CredentialPool.FeedbackKind.RATE_LIMITED]]

        when:
        events.clear()
        feedbackCalls.clear()
        stream(geminiModel, "goog-key-1", [new ScriptedHttpUpstream.Respond(401, "application/json",
                '{"error":{"code":401,"message":"API key not valid","status":"UNAUTHENTICATED"}}')])

        then:
        events[0].errorCode() == "llm.auth"
        feedbackCalls == [["google", CredentialPool.maskOf("goog-key-1"), CredentialPool.FeedbackKind.AUTH_FAILED]]
    }

    def "403 与 Gemini PERMISSION_DENIED 同归 auth（401/403 同判定档）"() {
        when: "openai 阵营 403 形态（配额/权限类拒绝也属凭证问题）"
        stream(openaiModel, "sk-forbidden-key",
                [new ScriptedHttpUpstream.Respond(403, "application/json",
                        '{"error":{"message":"Access denied","type":"insufficient_quota"}}')])

        then:
        events[0].errorCode() == "llm.auth"
        feedbackCalls == [["zai", "sk-***key", CredentialPool.FeedbackKind.AUTH_FAILED]]

        when: "Gemini error.status=PERMISSION_DENIED 枚举形态（HTTP 403）"
        events.clear()
        feedbackCalls.clear()
        stream(geminiModel, "goog-key-1", [new ScriptedHttpUpstream.Respond(403, "application/json",
                '{"error":{"code":403,"message":"Permission denied","status":"PERMISSION_DENIED"}}')])

        then:
        events[0].errorCode() == "llm.auth"
        feedbackCalls == [["google", CredentialPool.maskOf("goog-key-1"), CredentialPool.FeedbackKind.AUTH_FAILED]]
    }

    def "500 归一 api_error 直接进流"() {
        when:
        stream(openaiModel, "sk-x", [new ScriptedHttpUpstream.Respond(500, "application/json",
                '{"error":{"message":"Internal server error"}}')])

        then:
        events[0].errorCode() == "llm.api_error"
        events[0].errorMessage().contains("Internal server error")
        feedbackCalls == []
    }

    def "HTTP 200 但响应体是 JSON 错误形态：按 Content-Type 识别走错误归一"() {
        when:
        stream(openaiModel, "sk-x", [new ScriptedHttpUpstream.Respond(200, "application/json",
                '{"error":{"message":"Your request was rejected as a result of safety","code":"content_policy_filter"}}')])

        then: "不进 SSE 解析，按错误归一——文案探测照常工作（内容策略类命中）"
        events[0] instanceof ModelStreamEvent.Failed
                events[0].errorCode() == "llm.content_policy"
    }

    def "响应头迟迟不到归一 timeout（读超时兜底、重试一次后进流）"() {
        given: "上游收到请求后长时间不发响应头；读超时压到 1 秒"
        def quickParam = Mock(SystemParamService) { getLong(_, _) >> 1L }
        def quickGateway = new GatewayStreamFn(
                new DialectRegistry([new OpenAiCompatTranslator(
                        new StreamExchange(new HttpClientHolder(quickParam)), mediaFetcher, clock, idGen)]), pool,
                Mock(SlotResolver), Mock(CostRecorder))
        def server = new ScriptedHttpUpstream()
        // 连接级超时静默重试一次——两个剧本各喂一次命中（每次都长时间不发响应头）
        server.addScript("/chat/completions", [new ScriptedHttpUpstream.Delay(5000)])
        server.addScript("/chat/completions", [new ScriptedHttpUpstream.Delay(5000)])
        server.start()
        servers << server

        when:
        quickGateway.stream(new ModelRequest(
                new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "m", server.baseUrl(),
                        new ModelCapabilities(true, true, 128000, 4096), null, "chat_main"),
                [LlmMessage.user("你好")], "系统提示", [], GenerationParams.defaults(), "sk-x"),
                { event -> events << event } as StreamConsumer, CancellationSignal.create())

        then:
        events.last() instanceof ModelStreamEvent.Failed
        events.last().errorCode() == "llm.timeout"
    }

    def "ERROR 日志携带 errorCode 与 traceId"() {
        given:
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        def translatorLogger = (Logger) LoggerFactory.getLogger(OpenAiCompatTranslator.class)
        translatorLogger.addAppender(appender)

        when:
        stream(openaiModel, "sk-limited-key-1",
                [new ScriptedHttpUpstream.Respond(429, "application/json", '{"error":{"message":"limit"}}')])

        then:
        appender.list.any {
            it.level == Level.ERROR && it.formattedMessage.contains("llm.rate_limited")
                    && it.formattedMessage.contains("traceId")
        }

        cleanup:
        translatorLogger.detachAppender(appender)
    }
}
