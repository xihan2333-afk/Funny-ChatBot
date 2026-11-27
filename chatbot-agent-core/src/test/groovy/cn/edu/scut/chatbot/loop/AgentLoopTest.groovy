// 守护纪律：内核拿到的永远是流内消息不是异常——四条终止条件各自正确收尾
//（设计方案 2.2.2、宪法第五条循环内核纯净）
package cn.edu.scut.chatbot.loop

import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedStreamFn
import cn.edu.scut.chatbot.fixtures.StubTool
import cn.edu.scut.chatbot.loop.hooks.LoopHooks
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicLong

class AgentLoopTest extends Specification {

    def clock = new FixedClock()
    def idSeq = new AtomicLong(1000)
    def streamFn = new ScriptedStreamFn()
    def loop = new AgentLoop()

    def specFor(Map<String, AgentTool> tools, RunConfig config) {
        return new AgentLoop.RunSpec(
                9001L, "qq", "10000", "80001", 7001L, null,
                AgentContext.empty(), config,
                LoopHooks.builder(new TranscriptProjector()).build(),
                tools, streamFn, clock, { -> idSeq.incrementAndGet() },
                null, null)
    }

    def "自然结束：模型消息不含工具调用 Run以natural收尾"() {
        given:
        streamFn.addScript(ScriptedStreamFn.naturalText("大家好呀"))

        when:
        def outcome = loop.execute(specFor([:], RunConfig.turnDefaults()))

        then: "无 sendMessage 调用但有裸文本 → 走兜底投递（不沉默）"
        outcome.stopReason == AgentLoop.RunOutcome.STOP_NATURAL
        !outcome.silent
        outcome.fallbackText == "大家好呀"
        outcome.newMessages.size() == 1
        outcome.newMessages[0] instanceof AssistantMessage
    }

    def "异常终止：流Failed半截内容保留转录且不参与兜底"() {
        given:
        streamFn.addScript(ScriptedStreamFn.brokenStreamHalfText("半截内", "llm.network.disconnected"))

        when:
        def outcome = loop.execute(specFor([:], RunConfig.turnDefaults()))

        then:
        outcome.stopReason == AgentLoop.RunOutcome.STOP_ERROR
        outcome.newMessages.size() == 1
        def defective = outcome.newMessages[0] as AssistantMessage
        defective.isDefective()
        defective.textContent() == "半截内"
        defective.stopReason == AssistantMessage.STOP_REASON_ERROR
        outcome.fallbackText == null      // 残缺纪律：永不参与兜底
        outcome.silent                    // 也不算发言——沉默收尾
    }

    def "提前收敛：同批全部terminate才收敛 混合批次照常继续"() {
        given: "一个总 terminate 的工具与一个普通工具"
        def terminateTool = new StubTool("finisher", {
            new AgentToolResult(List.of(new cn.edu.scut.chatbot.loop.message.TextContent("终结")),
                    false, true, java.util.Map.of())
        })
        def normalTool = new StubTool("helper")
        // 第一轮：混合批次（terminate + 普通）→ 照常继续；第二轮：全部 terminate → 收敛
        streamFn.addScript(ScriptedStreamFn.interleavedToolCalls(
                "call-1", "finisher", "{}", "call-2", "helper", "{}"))
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-3", "finisher", "{}"))
        def tools = [finisher: terminateTool, helper: normalTool]

        when:
        def outcome = loop.execute(specFor(tools, RunConfig.turnDefaults()))

        then:
        outcome.stopReason == AgentLoop.RunOutcome.STOP_CONVERGED
        terminateTool.callCount() == 2
        normalTool.callCount() == 1
    }

    def "预算超限：shouldStopAfterTurn请求优雅停止"() {
        given: "剧本每次都吐工具调用（循环不会自然结束），预算闸在第 2 轮后请求停止"
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "helper", "{}"))
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-2", "helper", "{}"))
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-3", "helper", "{}"))
        def tools = [helper: new StubTool("helper")]
        def spec = new AgentLoop.RunSpec(
                9001L, "qq", "10000", "80001", 7001L, null,
                AgentContext.empty(), RunConfig.turnDefaults(),
                LoopHooks.builder(new TranscriptProjector())
                        .shouldStopAfterTurn({ turns, config -> turns >= 2 })
                        .build(),
                tools, streamFn, clock, { -> idSeq.incrementAndGet() }, null, null)

        when:
        def outcome = loop.execute(spec)

        then:
        outcome.stopReason == AgentLoop.RunOutcome.STOP_BUDGET_EXCEEDED
        tools.helper.callCount() == 2
    }

    def "卡死检测：N秒无增量置取消信号以aborted终止"() {
        given: "剧本停顿 3 秒不吐任何事件，卡死阈值 800ms"
        streamFn.addScript(ScriptedStreamFn.stallForever(3000))
        def config = new RunConfig(RunConfig.ExecutionForm.TURN, 2,
                java.time.Duration.ofSeconds(10), 800, null, false)

        when:
        def outcome = loop.execute(specFor([:], config))

        then:
        outcome.stopReason == AgentLoop.RunOutcome.STOP_ABORTED
    }

    def "发言三结局之正常交付：sendMessage调用过则以发言清单收尾"() {
        given:
        def sendTool = StubTool.sendMessageTool()
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "sendMessage",
                '{"parts":[{"part":"text","text":"你好呀"}]}'))
        streamFn.addScript(ScriptedStreamFn.naturalText("已经发出去啦"))

        when:
        def outcome = loop.execute(specFor([sendMessage: sendTool], RunConfig.turnDefaults()))

        then:
        outcome.stopReason == AgentLoop.RunOutcome.STOP_NATURAL
        !outcome.silent
        outcome.speechOutboxIds.size() == 1
        outcome.speechOutboxIds[0].startsWith("ob-")
    }

    def "双转录交付新产生集合：启动快照消息不出现在新产生集合"() {
        given: "上下文带历史消息，剧本自然结束"
        def history = [new cn.edu.scut.chatbot.loop.message.UserMessage(
                "hist-1", clock.instant(), 50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", "历史消息")))]
        def context = new AgentContext("sys", history, List.of(), new ModelConfig(null, GenerationParams.defaults()))
        streamFn.addScript(ScriptedStreamFn.naturalText("新回复"))

        when:
        def spec = new AgentLoop.RunSpec(9001L, "qq", "10000", "80001", 7001L, null,
                context, RunConfig.turnDefaults(),
                LoopHooks.builder(new TranscriptProjector()).build(),
                [:], streamFn, clock, { -> idSeq.incrementAndGet() }, null, null)
        def outcome = loop.execute(spec)

        then: "新产生集合只有本次 Run 新增（不含历史），交付不用差集计算"
        outcome.newMessages.size() == 1
        outcome.newMessages[0].messageId != "hist-1"
    }
}
