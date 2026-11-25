// 守护纪律：成本折算六位小数手算比对（命中去重不重复计输入价）、单价缺失落 null、
// 失败调用照记、is_replay 分离、TTFT 口径（请求发出到首个增量事件）、异步落账不阻塞主线程
// （宪法第七条 + 异步断言 Awaitility 禁 Thread.sleep；data-model 第 10/11 节）
package cn.edu.scut.chatbot.llm

import cn.edu.scut.chatbot.bootstrap.IdGenerator
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.engine.CostAwareStreamFn
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedHttpUpstream
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder
import cn.edu.scut.chatbot.llm.dialect.openai.OpenAiCompatTranslator
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta
import cn.edu.scut.chatbot.llm.gateway.StoredMediaFetcher
import cn.edu.scut.chatbot.llm.gateway.StreamExchange
import cn.edu.scut.chatbot.llm.gateway.TranslateContext
import cn.edu.scut.chatbot.llm.slot.*
import cn.edu.scut.chatbot.loop.*
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList

import static org.awaitility.Awaitility.await

class CostRecorderTest extends Specification {

    def mapper = Mock(LlmCallLogMapper)
    def catalog = Mock(ModelCatalog)
    def recorder = new CostRecorder(mapper, catalog)

    /** 消费者落库的捕获面（异步断言 Awaitility 等待） */
    def inserted = new CopyOnWriteArrayList<LlmCallLogDO>()

    def model = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "glm-4.6",
            "http://unused", new ModelCapabilities(true, true, 128000, 4096), null, "chat_main")

    def setup() {
        mapper.insertBatch(_) >> { rows ->
            inserted.addAll((List) rows[0])
            return ((List) rows[0]).size()
        }
        recorder.start()
    }

    def cleanup() {
        recorder.stop()
    }

    def usage(int input, int output, int cached) {
        return new AssistantMessage.TokenUsage(input, output, 0, cached, input)
    }

    def awaitRow() {
        await().atMost(java.time.Duration.ofSeconds(3)).until({ !inserted.isEmpty() })
        return inserted[0]
    }

    def "成本折算六位小数手算比对（cached 是 input 子集不重复计输入价）"() {
        given: "input=1000 cached=200 output=100；单价 input=2.0 cached=0.5 output=4.0（每百万）"
        catalog.pricesOf("zai", "glm-4.6") >> new cn.edu.scut.chatbot.loop.ModelPrices(
                new BigDecimal("2.0"), new BigDecimal("4.0"), new BigDecimal("0.5"))

        when:
        recorder.record(LlmCallMeta.of("turn", null), model,
                new CostSample(usage(1000, 100, 200), 50, 800, "stop", null))

        then: "手算：(1000-200)×2.0 + 200×0.5 + 100×4.0 = 2100 → 2100/1M = 0.002100"
        def row = awaitRow()
        row.costAmount == new BigDecimal("0.002100")
        row.inputTokens == 1000
        row.outputTokens == 100
        row.cachedTokens == 200
        row.ttftMs == 50
        row.totalMs == 800
        row.stopReason == "stop"
        row.slotCode == "chat_main"
    }

    def "单价缺失 cost 落 null（不阻塞落账）"() {
        given:
        catalog.pricesOf("zai", "glm-4.6") >> null

        when:
        recorder.record(LlmCallMeta.of("turn", null), model,
                new CostSample(usage(100, 10, 0), 0, 100, "stop", null))

        then:
        awaitRow().costAmount == null
    }

    def "失败调用照记（error_code 携带、无用量列 NULL）"() {
        when:
        recorder.record(LlmCallMeta.of("turn", "trace-1"), model,
                new CostSample(null, 0, 300, "error", "llm.rate_limited"))

        then:
        def row = awaitRow()
        row.errorCode == "llm.rate_limited"
        row.stopReason == "error"
        row.inputTokens == null
        row.traceId == "trace-1"
    }

    def "is_replay=1 评测重放成本单独记账不混线上"() {
        when:
        recorder.record(new LlmCallMeta("qq", "b", "g", 1L, "turn", "t", true), model,
                new CostSample(null, 0, 10, "stop", null))

        then:
        awaitRow().replay
    }

    def "TTFT 口径 = 请求发出到首个增量事件（假上游首分片延迟约 800ms）"() {
        given: "真翻译器 + 假上游（延迟 800ms 后推首个分片）"
        def paramService = Mock(SystemParamService) {
            getLong(_, _) >> 300L
            getInt(_, _) >> 300
        }
        def clock = new FixedClock()
        def mediaFetcher = (StoredMediaFetcher) { ref -> null }
        def translator = new OpenAiCompatTranslator(new StreamExchange(new HttpClientHolder(paramService)),
                mediaFetcher, clock, new IdGenerator(1, clock))
        def captured = new CopyOnWriteArrayList<CostSample>()
        def recording = new CostRecorder(mapper, catalog) {
            @Override
            void record(LlmCallMeta meta, ModelRef m, CostSample sample) {
                captured << sample
            }
        }
        def server = new ScriptedHttpUpstream()
        server.addScript("/chat/completions", [
                new ScriptedHttpUpstream.Delay(800),
                new ScriptedHttpUpstream.SseChunks([
                        'data: {"choices":[{"index":0,"delta":{"content":"首字"}}]}\n\n',
                        'data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}\n\n',
                        'data: [DONE]\n\n'])])
        server.start()
        def decorator = new CostAwareStreamFn(
                { request, consumer, signal ->
                    translator.translate(
                            new TranslateContext(request, consumer, signal))
                },
                recording, LlmCallMeta.of("turn", null))
        def withUrl = new ModelRef(model.dialect(), model.providerCode(), model.modelId(),
                server.baseUrl(), model.capabilities(), model.prices(), model.slotCode())

        when:
        decorator.stream(new ModelRequest(withUrl, [LlmMessage.user("你好")], "系统", [],
                GenerationParams.defaults(), "sk-x"), {}, CancellationSignal.create())

        then:
        captured.size() == 1
        captured[0].ttftMs() >= 700
        captured[0].totalMs() >= captured[0].ttftMs()
        captured[0].usage().inputTokens() == 10

        cleanup:
        server.close()
    }

    def "队列满丢弃不阻塞调用方（计量丢一条不伤主流程）"() {
        given: "不启动消费者线程——队列必然填满"
        def idleRecorder = new CostRecorder(mapper, catalog)

        when:
        (0..5000).each {
            idleRecorder.record(LlmCallMeta.of("turn", null), model,
                    new CostSample(null, 0, 1, "stop", null))
        }

        then:
        noExceptionThrown()
    }
}
