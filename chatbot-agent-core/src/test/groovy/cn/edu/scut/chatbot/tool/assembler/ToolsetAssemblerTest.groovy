// 守护纪律：装配即边界——Worker 无 sendMessage 是结构锁死非自觉（spec US5 验收场景 1）
package cn.edu.scut.chatbot.tool.assembler

import cn.edu.scut.chatbot.engine.intake.GroupConfigDO
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper
import cn.edu.scut.chatbot.fixtures.StubTool
import cn.edu.scut.chatbot.loop.AgentTool
import cn.edu.scut.chatbot.loop.RunConfig
import cn.edu.scut.chatbot.loop.ToolDescriptor
import cn.edu.scut.chatbot.tool.registry.ToolRegistry
import spock.lang.Specification

class ToolsetAssemblerTest extends Specification {

    def registry = Mock(ToolRegistry)
    def groupConfigMapper = Mock(GroupConfigMapper)
    def assembler = new ToolsetAssembler(registry, groupConfigMapper)

    private AgentTool tool(String name, ToolDescriptor.ToolSpeed speed) {
        return new StubTool(name) {
            @Override
            ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "测试工具", "{\"type\":\"object\"}", speed,
                        ToolDescriptor.EffectKind.PURE_QUERY, ToolDescriptor.ToolSource.BUILTIN, false)
            }
        }
    }

    private void toolsAvailable(Map<String, AgentTool> tools) {
        registry.snapshot() >> tools
        tools.keySet().each { registry.isGloballyEnabled(it) >> true }
    }

    def "四步装配：Turn形态保留快工具与sendMessage 排除慢工具"() {
        given:
        toolsAvailable([sendMessage: StubTool.sendMessageTool(),
                        fetchWeb   : tool("fetchWeb", ToolDescriptor.ToolSpeed.FAST),
                        runCode    : tool("runCode", ToolDescriptor.ToolSpeed.SLOW)])
        groupConfigMapper.selectByGroup(*_) >> null

        when:
        def assembled = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001")

        then:
        assembled.collect { it.descriptor().name() } as Set == ["sendMessage", "fetchWeb"] as Set
    }

    def "Worker形态按归属白名单装配：白名单慢工具在 读群类快工具不在 HTTP泛化天然在 sendMessage绝不在"() {
        given: "白名单内慢工具 runCode、非白名单读群类快工具 queryHistory、HTTP 泛化工具、sendMessage"
        toolsAvailable([sendMessage : StubTool.sendMessageTool(),
                        runCode     : tool("runCode", ToolDescriptor.ToolSpeed.SLOW),
                        queryHistory: tool("queryHistory", ToolDescriptor.ToolSpeed.FAST),
                        httpWeather : httpGenericTool("httpWeather", ToolDescriptor.ToolSpeed.FAST)])
        groupConfigMapper.selectByGroup(*_) >> null

        when:
        def names = assembler.assemble(RunConfig.ExecutionForm.WORKER, "qq", "10000", "80001")
                .collect { it.descriptor().name() } as Set

        then: "第四步是 Worker 归属白名单（设计 2.2.3.7）：白名单工具 + HTTP 泛化在列"
        names.contains("runCode")
        names.contains("httpWeather")
        and: "读群类快工具不在 Worker 工具集（Worker 输入自包含，不读群聊）"
        !names.contains("queryHistory")
        and: "sendMessage 不在白名单——发言出口只属于面向群聊的形态（负向断言必测）"
        !names.contains("sendMessage")
    }

    def "白名单只是Worker的口径：Turn形态保留非白名单快工具"() {
        given:
        toolsAvailable([queryHistory: tool("queryHistory", ToolDescriptor.ToolSpeed.FAST),
                        runCode     : tool("runCode", ToolDescriptor.ToolSpeed.SLOW)])
        groupConfigMapper.selectByGroup(*_) >> null

        when:
        def names = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001")
                .collect { it.descriptor().name() } as Set

        then: "Turn 不做归属裁配：快工具在（慢工具仍被第二步排除）"
        names == ["queryHistory"] as Set
    }

    private AgentTool httpGenericTool(String name, ToolDescriptor.ToolSpeed speed) {
        return new StubTool(name) {
            @Override
            ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "泛化工具", "{\"type\":\"object\"}", speed,
                        ToolDescriptor.EffectKind.EXTERNAL_API, ToolDescriptor.ToolSource.HTTP_GENERIC, false)
            }
        }
    }

    def "全局禁用的工具不装配（第一步）"() {
        given:
        def disabledTool = tool("fetchWeb", ToolDescriptor.ToolSpeed.FAST)
        registry.snapshot() >> [fetchWeb: disabledTool]
        registry.isGloballyEnabled("fetchWeb") >> false
        groupConfigMapper.selectByGroup(*_) >> null

        when:
        def assembled = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001")

        then:
        assembled.isEmpty()
    }

    def "群级禁用的工具该群不装配 其他群不受影响（第三步）"() {
        given:
        toolsAvailable([fetchWeb: tool("fetchWeb", ToolDescriptor.ToolSpeed.FAST)])
        def config = new GroupConfigDO()
        config.setDisabledTools('["fetchWeb"]')
        groupConfigMapper.selectByGroup("qq", "10000", "80001") >> config
        groupConfigMapper.selectByGroup("qq", "10000", "80002") >> null

        when:
        def groupA = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001")
        def groupB = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80002")

        then:
        groupA.isEmpty()
        groupB.size() == 1
    }
}
