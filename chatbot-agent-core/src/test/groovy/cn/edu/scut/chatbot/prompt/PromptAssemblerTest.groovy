// 守护纪律：同样输入同样输出（确定性）；层序与形态裁配正确（Worker 无身份层、时间只在现注区）
//——spec US6 验收场景 1/2
package cn.edu.scut.chatbot.prompt

import cn.edu.scut.chatbot.engine.intake.GroupConfigDO
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper
import cn.edu.scut.chatbot.engine.intake.GroupDO
import cn.edu.scut.chatbot.engine.intake.GroupMapper
import cn.edu.scut.chatbot.loop.RunConfig
import cn.edu.scut.chatbot.memory.persona.PersonaDO
import cn.edu.scut.chatbot.memory.persona.PersonaResolver
import spock.lang.Specification

class PromptAssemblerTest extends Specification {

    def fragmentMapper = Mock(PromptFragmentMapper)
    def groupMapper = Mock(GroupMapper)
    def groupConfigMapper = Mock(GroupConfigMapper)
    def personaResolver = Mock(PersonaResolver)
    def skillRenderer = Mock(SkillDirectoryRenderer)
    def groupProfileMapper = Mock(cn.edu.scut.chatbot.memory.profile.GroupProfileMapper)

    def assembler = new PromptAssembler(fragmentMapper, groupMapper, groupConfigMapper,
            personaResolver, skillRenderer, groupProfileMapper)

    private PromptFragmentDO frag(String key, String layer, String formFit, String content) {
        def fragment = new PromptFragmentDO()
        fragment.setFragmentKey(key)
        fragment.setLayer(layer)
        fragment.setFormFit(formFit)
        fragment.setContent(content)
        fragment.setVariables("[]")
        return fragment
    }

    private void persona() {
        def personaDO = new PersonaDO()
        personaDO.setName("小趣")
        personaResolver.resolve(*_) >> personaDO
        personaResolver.selfSettingOf(_) >> "爱玩梗的群聊伙伴"
    }

    def "同样输入组装同样输出（确定性断言）"() {
        given:
        persona()
        fragmentMapper.selectPublishedByLayer("identity") >> [frag("skeleton", "identity", "turn,lightTask", "你是 {{persona.name}}")]
        fragmentMapper.selectPublishedByLayer("protocol") >> []
        groupMapper.selectByGroup(*_) >> null
        skillRenderer.renderDirectory(_) >> ""

        when:
        def first = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null)
        def second = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null)

        then:
        first == second
        first.contains("小趣")
    }

    def "Turn形态层序身份群上下文能力协议 且当前时间不进systemPrompt"() {
        given:
        persona()
        fragmentMapper.selectPublishedByLayer("identity") >> [frag("skeleton", "identity", "turn,lightTask", "身份层内容")]
        fragmentMapper.selectPublishedByLayer("protocol") >> [frag("speech", "protocol", "turn,lightTask", "发言协议内容")]
        def group = new GroupDO()
        group.setGroupName("测试群")
        groupMapper.selectByGroup(*_) >> group
        groupConfigMapper.selectByGroup(*_) >> null
        skillRenderer.renderDirectory(_) >> ""

        when:
        def prompt = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null)

        then: "层序：身份 → 群上下文 → 能力协议（无任务层）"
        prompt.indexOf("身份层内容") < prompt.indexOf("群上下文")
        prompt.indexOf("群上下文") < prompt.indexOf("发言协议内容")
        !prompt.contains("【任务】")
        // 当前时间不进 systemPrompt（由现注区注入——Run 的 transformContext）
        !prompt.contains("当前时间")
    }

    def "Worker形态无身份无群上下文无发言协议 任务层为执行提示词"() {
        given:
        persona()
        fragmentMapper.selectPublishedByLayer("identity") >> [frag("skeleton", "identity", "turn,lightTask", "身份层内容")]
        fragmentMapper.selectPublishedByLayer("protocol") >> [frag("speech", "protocol", "turn,lightTask", "发言协议内容"),
                                                              frag("tools", "protocol", "turn,lightTask,worker", "工具总则内容")]
        skillRenderer.renderDirectory(_) >> ""
        groupMapper.selectByGroup(*_) >> null

        when:
        def prompt = assembler.assemble(RunConfig.ExecutionForm.WORKER, "qq", "10000", "80001", "执行这个调研任务")

        then:
        !prompt.contains("身份层内容")       // 无身份层
        !prompt.contains("群上下文")          // 无群上下文层
        !prompt.contains("发言协议内容")      // 无发言协议
        prompt.contains("工具总则内容")       // 能力协议的工具总则保留
        prompt.contains("【任务】")           // 任务层为执行提示词
        prompt.contains("执行这个调研任务")
    }

    def "群配置的群规XML定界注入（US7 不可信变量通道）"() {
        given:
        persona()
        fragmentMapper.selectPublishedByLayer("identity") >> []
        fragmentMapper.selectPublishedByLayer("protocol") >> []
        def group = new GroupDO()
        group.setGroupName("测试群")
        def config = new GroupConfigDO()
        config.setGroupRules("不许发广告")
        groupMapper.selectByGroup(*_) >> group
        groupConfigMapper.selectByGroup(*_) >> config
        skillRenderer.renderDirectory(_) >> ""

        when:
        def prompt = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null)

        then:
        prompt.contains('<untrusted src="variable">')
        prompt.contains("</untrusted>")
        prompt.contains("不许发广告")
    }

    def "未定义变量渲染为空不中断"() {
        given:
        persona()
        fragmentMapper.selectPublishedByLayer("identity") >> [frag("x", "identity", "all", "你好{{not.declared}}大家好")]
        fragmentMapper.selectPublishedByLayer("protocol") >> []
        groupMapper.selectByGroup(*_) >> null
        skillRenderer.renderDirectory(_) >> ""

        when:
        def prompt = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null)

        then:
        prompt.contains("大家好")
        !prompt.contains("not.declared")
    }

    def "群级禁用技能清单逗号分隔传入技能目录渲染（技能两级开关的第二级）"() {
        given: "该群禁用早报与点歌两个技能"
        persona()
        fragmentMapper.selectPublishedByLayer("identity") >> []
        fragmentMapper.selectPublishedByLayer("protocol") >> []
        def config = new GroupConfigDO()
        config.setDisabledSkills('["早报","点歌"]')
        groupConfigMapper.selectByGroup(*_) >> config
        def receivedContext = []
        skillRenderer.renderDirectory(_) >> { String ctx ->
            receivedContext << ctx
            "【可用技能】\n- 天气查询"
        }

        when:
        def prompt = assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null)

        then: "目录渲染收到逗号分隔的过滤上下文（目录据此过滤条目）"
        receivedContext == ["早报,点歌"]
        and: "渲染出的目录进入能力协议层"
        prompt.contains("【可用技能】")
    }

    def "禁用技能清单为空或无群会话时不过滤（null 上下文进目录渲染）"() {
        given:
        persona()
        fragmentMapper.selectPublishedByLayer("identity") >> []
        fragmentMapper.selectPublishedByLayer("protocol") >> []
        def config = new GroupConfigDO()
        config.setDisabledSkills(emptyJson)
        groupConfigMapper.selectByGroup(*_) >> config
        def receivedContext = []
        skillRenderer.renderDirectory(_) >> { String ctx -> receivedContext << ctx; "" }

        when:
        assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", groupId, null)

        then:
        receivedContext == [null]

        where:
        emptyJson | groupId
        "[]"      | "80001"
        null      | "80001"
        null      | null
    }
}
