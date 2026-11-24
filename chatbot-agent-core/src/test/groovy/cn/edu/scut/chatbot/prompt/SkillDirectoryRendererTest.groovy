// 守护纪律：目录只列「全局启用且已发布且该群未禁用」；禁用按名精确匹配
// （子串包含会把 readSkillPro 误杀成 readSkill）——三级渐进披露第一级
package cn.edu.scut.chatbot.prompt

import cn.edu.scut.chatbot.skill.SkillDO
import cn.edu.scut.chatbot.skill.SkillMapper
import spock.lang.Specification

class SkillDirectoryRendererTest extends Specification {

    def skillMapper = Mock(SkillMapper)

    SkillDirectoryRenderer renderer = new SkillDirectoryRenderer(skillMapper)

    private SkillDO skill(String name, String description, int enabled, String status) {
        SkillDO skill = new SkillDO()
        skill.setName(name)
        skill.setDescription(description)
        skill.setEnabled(enabled)
        skill.setStatus(status)
        return skill
    }

    def "目录只列启用且已发布的技能 一行一条含读全文指引"() {
        given:
        skillMapper.selectAll() >> [
                skill("早报", "每天早上发一份群早报", 1, "published"),
                skill("草稿技能", "还没写完", 1, "draft"),
                skill("下线技能", "被关闭了", 0, "published")]

        when:
        def directory = renderer.renderDirectory(null)

        then:
        directory.contains("- 技能【早报】每天早上发一份群早报（用 readSkill 读全文）")
        !directory.contains("草稿技能")
        !directory.contains("下线技能")
        directory.startsWith("【可用技能】")
    }

    def "群级禁用按名精确匹配 前缀相同的兄弟技能不误杀"() {
        given: "群禁用 readSkill；注册表另有前缀相同的 readSkillPro"
        skillMapper.selectAll() >> [
                skill("readSkill", "读一个技能的全文", 1, "published"),
                skill("readSkillPro", "读技能全文的高级形态", 1, "published"),
                skill("早报", "每天早上发一份群早报", 1, "published")]

        when:
        def directory = renderer.renderDirectory("readSkill")

        then: "readSkill 被过滤，readSkillPro 仍在（精确匹配非子串包含）"
        !directory.contains("【readSkill】")
        directory.contains("【readSkillPro】")
        directory.contains("【早报】")
    }

    def "逗号分隔多技能过滤 空白容错"() {
        given:
        skillMapper.selectAll() >> [
                skill("早报", "a", 1, "published"),
                skill("点歌", "b", 1, "published"),
                skill("天气", "c", 1, "published")]

        when:
        def directory = renderer.renderDirectory(" 早报 , 点歌 ,,")

        then:
        !directory.contains("【早报】")
        !directory.contains("【点歌】")
        directory.contains("【天气】")
    }

    def "空串上下文不过滤"() {
        given:
        skillMapper.selectAll() >> [skill("早报", "a", 1, "published")]

        when:
        def directory = renderer.renderDirectory(blankContext)

        then:
        directory.contains("【早报】")

        where:
        blankContext << [null, "", "   "]
    }

    def "过滤后无技能返回空串（层内不出现空目录头）"() {
        given:
        skillMapper.selectAll() >> [skill("早报", "a", 1, "published")]

        when:
        def directory = renderer.renderDirectory("早报")

        then:
        directory == ""
    }
}
