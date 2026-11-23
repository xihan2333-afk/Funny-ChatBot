// 守护纪律：工具三段式执行（prepare→execute→finalize）与批次规则——契约 loop-kernel.md 第 4 节
package cn.edu.scut.chatbot.loop

import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedStreamFn
import cn.edu.scut.chatbot.fixtures.StubTool
import cn.edu.scut.chatbot.loop.hooks.LoopHooks
import cn.edu.scut.chatbot.loop.message.ContentPart
import cn.edu.scut.chatbot.loop.message.ToolResultMessage
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicLong

class ToolExecutionPipelineTest extends Specification {

    def clock = new FixedClock()
    def idSeq = new AtomicLong(3000)
    def streamFn = new ScriptedStreamFn()
    def loop = new AgentLoop()

    def specFor(Map<String, AgentTool> tools) {
        return new AgentLoop.RunSpec(
                9006L, "qq", "10000", "80001", null, null,
                AgentContext.empty(), RunConfig.turnDefaults(),
                LoopHooks.builder(new TranscriptProjector()).build(),
                tools, streamFn, clock, { -> idSeq.incrementAndGet() }, null, null)
    }

    def "prepare不过关直接判错误结果不执行：参数JSON截断"() {
        given: "工具调用参数 JSON 只有半截（截断证据——全部标记失败不执行）"
        streamFn.addScript(ScriptedStreamFn.truncatedToolArguments("call-1", "helper", '{"key": "val'))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))
        def tool = new StubTool("helper")

        when:
        def outcome = loop.execute(specFor([helper: tool]))

        then: "工具从未执行，转录里是 isError 的结果"
        tool.callCount() == 0
        def result = outcome.newMessages.find { it instanceof ToolResultMessage } as ToolResultMessage
        result.isError()
        result.content()[0] instanceof cn.edu.scut.chatbot.loop.message.TextContent
        result.content()[0].text().contains("不是合法 JSON")
    }

    def "工具不存在：判错误结果（本次 Run 工具清单里没有它）"() {
        given:
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "notRegistered", "{}"))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))

        when:
        def outcome = loop.execute(specFor([:]))

        then:
        def result = outcome.newMessages.find { it instanceof ToolResultMessage } as ToolResultMessage
        result.isError()
        result.content()[0].text().contains("工具不存在")
    }

    def "失败异常归一化isError：错误文本不当content正常返回"() {
        given: "工具执行抛 ToolExecutionException"
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "broken", "{}"))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))
        def failing = StubTool.failing("broken", "沙箱调用超时")

        when:
        def outcome = loop.execute(specFor([broken: failing]))

        then:
        def result = outcome.newMessages.find { it instanceof ToolResultMessage } as ToolResultMessage
        result.isError()
        result.content()[0].text().contains("沙箱调用超时")
    }

    def "beforeToolCall拦截：以拦截结果入转录不执行真工具"() {
        given:
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "helper", "{}"))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))
        def tool = new StubTool("helper")
        def hooks = LoopHooks.builder(new TranscriptProjector())
                .beforeToolCall({ new LoopHooks.BeforeToolCallHook.Interception(AgentToolResult.error("策略拦截：该操作被禁止")) })
                .build()

        when:
        def spec = new AgentLoop.RunSpec(9007L, "qq", "10000", "80001", null, null,
                AgentContext.empty(), RunConfig.turnDefaults(), hooks,
                [helper: tool], streamFn, clock, { -> idSeq.incrementAndGet() }, null, null)
        def outcome = loop.execute(spec)

        then:
        tool.callCount() == 0
        def result = outcome.newMessages.find { it instanceof ToolResultMessage } as ToolResultMessage
        result.isError()
        result.content()[0].text().contains("策略拦截")
    }

    def "afterToolCall逐字段覆盖：定稿前改写结果内容"() {
        given:
        streamFn.addScript(ScriptedStreamFn.toolCallThen("call-1", "helper", "{}"))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))
        def hooks = LoopHooks.builder(new TranscriptProjector())
                .afterToolCall({ invocation, result ->
                    AgentToolResult.text("改写后的结果")
                })
                .build()

        when:
        def spec = new AgentLoop.RunSpec(9008L, "qq", "10000", "80001", null, null,
                AgentContext.empty(), RunConfig.turnDefaults(), hooks,
                [helper: new StubTool("helper")], streamFn, clock, { -> idSeq.incrementAndGet() }, null, null)
        def outcome = loop.execute(spec)

        then:
        def result = outcome.newMessages.find { it instanceof ToolResultMessage } as ToolResultMessage
        result.content()[0].text() == "改写后的结果"
    }

    def "并行批次结果按调用顺序入转录"() {
        given: "两个 parallelizable 工具，第一个故意慢（完成顺序颠倒）"
        def slowTool = new StubTool("slowOne", {
            Thread.sleep(150)
            AgentToolResult.text("慢工具结果")
        })
        def fastTool = new StubTool("fastOne", { AgentToolResult.text("快工具结果") })
        // 改造为 parallelizable
        slowTool = parallelizable(slowTool)
        fastTool = parallelizable(fastTool)
        streamFn.addScript(ScriptedStreamFn.interleavedToolCalls(
                "call-A", "slowOne", "{}", "call-B", "fastOne", "{}"))
        streamFn.addScript(ScriptedStreamFn.naturalText("完成"))

        when:
        def outcome = loop.execute(specFor([slowOne: slowTool, fastOne: fastTool]))

        then: "慢工具先调用（index 0）但后完成——转录仍按调用顺序：slowOne 在前"
        def toolResults = outcome.newMessages.findAll { it instanceof ToolResultMessage }
        toolResults.size() == 2
        (toolResults[0] as ToolResultMessage).toolName() == "slowOne"
        (toolResults[1] as ToolResultMessage).toolName() == "fastOne"
    }

    private AgentTool parallelizable(AgentTool delegate) {
        def original = delegate.descriptor()
        def parallelDescriptor = new ToolDescriptor(original.name(), original.description(),
                original.parametersJsonSchema(), original.speed(), original.effectKind(),
                original.source(), true)
        return new AgentTool() {
            @Override
            ToolDescriptor descriptor() { return parallelDescriptor }

            @Override
            AgentToolResult execute(ToolInvocation invocation) { return delegate.execute(invocation) }
        }
    }
}
