// 守护纪律：钩子三条框架强制纪律（不得抛异常/返回空保持现状/改动只作用副本）
//——契约 loop-kernel.md 第 2 节
package cn.edu.scut.chatbot.loop

import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedStreamFn
import cn.edu.scut.chatbot.fixtures.StubTool
import cn.edu.scut.chatbot.loop.hooks.LoopHooks
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import cn.edu.scut.chatbot.loop.message.ToolResultMessage
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicLong

class HookDisciplineTest extends Specification {

    def clock = new FixedClock()
    def idSeq = new AtomicLong(2000)
    def streamFn = new ScriptedStreamFn()
    def loop = new AgentLoop()

    def "钩子抛异常降级不生效 推理照常完成"() {
        given: "transformContext 抛异常、convertToLlm 抛异常——都不许拖垮推理"
        def history = [new cn.edu.scut.chatbot.loop.message.UserMessage(
                "hist-1", clock.instant(), 50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", "你好")))]
        def context = new AgentContext("sys", history, List.of(), new ModelConfig(null, GenerationParams.defaults()))
        streamFn.addScript(ScriptedStreamFn.naturalText("回复"))
        def spec = new AgentLoop.RunSpec(9002L, "qq", "10000", "80001", null, null,
                context, RunConfig.turnDefaults(),
                LoopHooks.builder({ throw new IllegalStateException("投影钩子炸了") })
                        .transformContext({ throw new IllegalStateException("变换钩子炸了") })
                        .build(),
                [:], streamFn, clock, { -> idSeq.incrementAndGet() }, null, null)

        when:
        def outcome = loop.execute(spec)

        then: "两个钩子都降级（空投影），Run 仍以自然结束收尾"
        outcome.stopReason == AgentLoop.RunOutcome.STOP_NATURAL
        outcome.fallbackText == "回复"
    }

    def "钩子返回空保持现状：返回null时上下文原样传入下一次调用"() {
        given: "transformContext 返回 null；剧本两轮，观察第二次调用收到的投影仍含历史"
        def history = [new cn.edu.scut.chatbot.loop.message.UserMessage(
                "hist-1", clock.instant(), 50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", "历史")))]
        def context = new AgentContext("sys", history, List.of(), new ModelConfig(null, GenerationParams.defaults()))
        def receivedProjections = []
        def trackingProjector = { messages ->
            def projected = new TranscriptProjector().convert(messages)
            receivedProjections << projected.size()
            return projected
        }
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "helper", "{}"))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))
        def spec = new AgentLoop.RunSpec(9003L, "qq", "10000", "80001", null, null,
                context, RunConfig.turnDefaults(),
                LoopHooks.builder(trackingProjector)
                        .transformContext({ null })
                        .build(),
                [helper: new StubTool("helper")], streamFn, clock,
                { -> idSeq.incrementAndGet() }, null, null)

        when:
        loop.execute(spec)

        then: "第二轮投影 = 历史 1 + 第一轮 assistant 1 + 工具结果 1 = 3（transformContext 返回空未丢弃任何内容）"
        receivedProjections.size() == 2
        receivedProjections[0] == 1
        receivedProjections[1] == 3
    }

    def "改动只作用副本：钩子修改消息列表不影响持久化历史"() {
        given: "transformContext 拿到上下文后构造新对象追加条目；persister 收到的轮次消息不含追加内容"
        def history = [new cn.edu.scut.chatbot.loop.message.UserMessage(
                "hist-1", clock.instant(), 50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", "历史")))]
        def context = new AgentContext("sys", history, List.of(), new ModelConfig(null, GenerationParams.defaults()))
        def persistedBatches = []
        streamFn.addScript(ScriptedStreamFn.naturalText("回复"))
        def spec = new AgentLoop.RunSpec(9004L, "qq", "10000", "80001", null, null,
                context, RunConfig.turnDefaults(),
                LoopHooks.builder(new TranscriptProjector())
                        .transformContext({ original ->
                            // 钩子构造新上下文追加一条现注——只作用本次调用副本
                            new AgentContext(original.systemPrompt(),
                                    original.messages() + [new cn.edu.scut.chatbot.loop.message.SystemNote(
                                            "note-1", clock.instant(), 50, null, "任务清单", "[现注] 进行中任务：无")],
                                    original.tools(), original.modelConfig())
                        })
                        .build(),
                [:], streamFn, clock, { -> idSeq.incrementAndGet() },
                { runId, messages -> persistedBatches << messages }, null)

        when:
        def outcome = loop.execute(spec)

        then: "落库的是轮次真实产出（assistant 一条），钩子追加的现注不落库（不污染持久化历史）"
        persistedBatches.size() == 1
        persistedBatches[0].size() == 1
        persistedBatches[0][0] instanceof AssistantMessage
        outcome.newMessages.every { it.messageId != "note-1" }
    }

    def "afterToolCall抛异常保持原结果 工具结果照常入转录"() {
        given:
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "helper", "{}"))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))
        def spec = new AgentLoop.RunSpec(9005L, "qq", "10000", "80001", null, null,
                AgentContext.empty(), RunConfig.turnDefaults(),
                LoopHooks.builder(new TranscriptProjector())
                        .afterToolCall({ invocation, result -> throw new IllegalStateException("定稿钩子炸了") })
                        .build(),
                [helper: new StubTool("helper")], streamFn, clock,
                { -> idSeq.incrementAndGet() }, null, null)

        when:
        def outcome = loop.execute(spec)

        then:
        outcome.newMessages.find { it instanceof ToolResultMessage && it.toolName == "helper" } != null
    }
}
