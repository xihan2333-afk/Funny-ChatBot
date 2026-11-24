// 守护纪律：无有效 Mandate 的 Task 不允许存在（spec US4；策略变更失格取消进审计）
package cn.edu.scut.chatbot.engine.task

import cn.edu.scut.chatbot.exception.CommandRejectedException
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper
import spock.lang.Specification

class MandateTest extends Specification {

    def groupConfigMapper = Mock(GroupConfigMapper)
    def guard = new MandateGuard(groupConfigMapper)

    private GroupConfigDO config(String memberCategories, String autonomousCategories) {
        def groupConfig = new GroupConfigDO()
        groupConfig.setUserBlacklist("[]")
        groupConfig.setAllowedMemberTaskCategories(memberCategories)
        groupConfig.setAllowedAutonomousCategories(autonomousCategories)
        return groupConfig
    }

    private TaskDO task(String mandateSource, String category) {
        def taskDO = new TaskDO()
        taskDO.setPlatform("qq")
        taskDO.setBotId("10000")
        taskDO.setGroupId("80001")
        taskDO.setMandateSource(mandateSource)
        taskDO.setCategory(category)
        return taskDO
    }

    def "admin 管理员指令直过"() {
        given:
        groupConfigMapper.selectByGroup(*_) >> config("[]", "[]")

        expect:
        guard.assertCreatable(task(TaskDO.MANDATE_ADMIN, "research")) == null
    }

    def "member 须群策略允许该类别"() {
        given:
        groupConfigMapper.selectByGroup(*_) >> config('["remind","topic_follow"]', "[]")

        when:
        guard.assertCreatable(task(TaskDO.MANDATE_MEMBER, "remind"))

        then:
        noExceptionThrown()

        when: "策略不允许的类别"
        guard.assertCreatable(task(TaskDO.MANDATE_MEMBER, "research"))

        then:
        def rejected = thrown(CommandRejectedException)
        rejected.reason.contains("research")
        rejected.reason.contains("允许清单")
    }

    def "autonomous 须在允许清单"() {
        given:
        groupConfigMapper.selectByGroup(*_) >> config("[]", '["daily_greeting"]')

        when:
        guard.assertCreatable(task(TaskDO.MANDATE_AUTONOMOUS, "daily_greeting"))

        then:
        noExceptionThrown()

        when: "清单外类别"
        guard.assertCreatable(task(TaskDO.MANDATE_AUTONOMOUS, "image"))

        then:
        thrown(CommandRejectedException)
    }

    def "策略变更存量重验证失格返回true 进审计取消"() {
        given: "原策略允许 image，存量有 autonomous image 任务；策略收窄后重验证"
        groupConfigMapper.selectByGroup(*_) >> config("[]", "[]")

        expect:
        guard.isDisqualified(task(TaskDO.MANDATE_AUTONOMOUS, "image"))
        !guard.isDisqualified(task(TaskDO.MANDATE_ADMIN, "image"))
    }

    def "未知授权来源拒绝"() {
        given:
        groupConfigMapper.selectByGroup(*_) >> config("[]", "[]")

        when:
        guard.assertCreatable(task("stranger", "remind"))

        then:
        thrown(CommandRejectedException)
    }
}
