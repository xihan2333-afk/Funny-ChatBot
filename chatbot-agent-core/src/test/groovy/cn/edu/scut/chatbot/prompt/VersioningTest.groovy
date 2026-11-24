// 守护纪律：新 Run 生效、进行中 Run 不受扰；版本可回滚且号只增不减（spec US6 验收场景 3/7）
package cn.edu.scut.chatbot.prompt

import cn.edu.scut.chatbot.exception.CommandRejectedException
import cn.edu.scut.chatbot.skill.SkillDO
import cn.edu.scut.chatbot.skill.SkillMapper
import cn.edu.scut.chatbot.tool.registry.ToolRegistry
import spock.lang.Specification

class VersioningTest extends Specification {

    def fragmentMapper = Mock(PromptFragmentMapper)
    def skillMapper = Mock(SkillMapper)
    def toolRegistry = Mock(ToolRegistry)

    def service = new ContentVersioningService(fragmentMapper, skillMapper, toolRegistry)

    private PromptFragmentDO fragment(String key, String content, int version, int locked = 0, String status = "published") {
        def promptFragment = new PromptFragmentDO()
        promptFragment.setId(101L)
        promptFragment.setFragmentKey(key)
        promptFragment.setContent(content)
        promptFragment.setLayer("identity")
        promptFragment.setFormFit("all")
        promptFragment.setVariables("[]")
        promptFragment.setVersionNo(version)
        promptFragment.setBuiltinLocked(locked)
        promptFragment.setStatus(status)
        return promptFragment
    }

    def "草稿编辑不影响Run 组装器只读published"() {
        given: "已发布片段 v2"
        fragmentMapper.selectByKey("test.greeting") >> fragment("test.greeting", "旧内容", 2)

        when: "草稿编辑（保存为 draft 态）"
        def edited = fragment("test.greeting", "新内容", 2, 0, "published")
        service.saveFragment(edited)

        then: "保存后是 draft 态（组装器 selectPublishedByLayer 不会读它）"
        1 * fragmentMapper.updateFragment({ it.status == "draft" && it.content == "新内容" })
    }

    def "发布留痕且版本号递增"() {
        given:
        fragmentMapper.selectByKey("test.greeting") >> fragment("test.greeting", "内容", 2)
        fragmentMapper.selectMaxVersion("fragment", 101L) >> 2

        when:
        service.publishFragment("test.greeting")

        then:
        1 * fragmentMapper.insertVersion({ it.versionNo == 3 && it.entityKind == "fragment" })
        1 * fragmentMapper.updateFragment({ it.versionNo == 3 && it.status == "published" })
    }

    def "回滚旧快照写回主表再走一次发布 版本号递增留痕"() {
        given: "当前 v3，回滚到 v1"
        fragmentMapper.selectByKey("test.greeting") >> fragment("test.greeting", "v3内容", 3)
        def v1Snapshot = new ContentVersionDO()
        v1Snapshot.setEntityKind("fragment")
        v1Snapshot.setVersionNo(1)
        v1Snapshot.setSnapshot('{"id":101,"fragmentKey":"test.greeting","layer":"identity","formFit":"all","title":null,"content":"v1内容","variables":"[]","builtinLocked":0,"status":"published","versionNo":1}')
        fragmentMapper.selectVersion("fragment", 101L, 1) >> v1Snapshot

        when:
        service.rollbackFragment("test.greeting", 1)

        then: "写回旧内容 + 两次 update（写回 draft 一次、发布 published 一次）+ 版本 4 留痕"
        1 * fragmentMapper.updateFragment({ it.content == "v1内容" })
        1 * fragmentMapper.insertVersion({ it.versionNo == 4 })
        1 * fragmentMapper.updateFragment({ it.versionNo == 4 && it.status == "published" })
    }

    def "builtin_locked 片段不可删改"() {
        given:
        fragmentMapper.selectByKey("builtin.skeleton") >> fragment("builtin.skeleton", "锁定内容", 1, 1)

        when:
        service.saveFragment(fragment("builtin.skeleton", "篡改", 1, 1))

        then:
        thrown(CommandRejectedException)

        when:
        service.deleteFragment("builtin.skeleton")

        then:
        thrown(CommandRejectedException)
    }

    def "模板引用未声明变量保存失败（拼错拦在发布前）"() {
        when: "content 引用 {{persona.nam}}（拼错）而声明清单只有 persona.name"
        service.validateTemplateVariables("你好 {{persona.nam}}", '["persona.name"]')

        then:
        def rejected = thrown(CommandRejectedException)
        rejected.reason.contains("persona.nam")
    }

    def "路径前缀与通配声明合法"() {
        expect:
        service.validateTemplateVariables("{{persona.name}} {{persona.selfSetting}}", '["persona.*"]')
        service.validateTemplateVariables("{{persona.name}}", '["persona.name"]')
    }

    def "Skill 描述超长保存拒绝"() {
        given:
        skillMapper.selectByName("big") >> null

        when:
        def skill = new SkillDO()
        skill.setName("big")
        skill.setDescription("a" * 201)
        skill.setContent("内容")
        service.saveSkill(skill)

        then:
        thrown(CommandRejectedException)
        0 * skillMapper.insertSkill(_)
    }
}
