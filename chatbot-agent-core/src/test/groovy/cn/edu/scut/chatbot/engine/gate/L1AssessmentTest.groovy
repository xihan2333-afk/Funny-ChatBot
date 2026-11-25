// 守护纪律：评估失败不放大为系统故障——退避重试、耗尽保守 ignore 降级（spec US2、宪法第三条）
package cn.edu.scut.chatbot.engine.gate

import cn.edu.scut.chatbot.engine.intake.TriggerEventDO
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.ScriptedL1Assessor
import spock.lang.Specification

import java.time.LocalDateTime

class L1AssessmentTest extends Specification {

    def mapper = Mock(TriggerEventMapper)
    def assessor = new ScriptedL1Assessor()
    def clock = new FixedClock()

    def service = new L1BatchAssessor(mapper, assessor,
            { name -> "评估提示词" }, { p, b, g, limit -> [] }, { p, b, g -> "" },
            { p, b, g -> "" }, { p, b, g, sender -> null }, { p, b, g -> "小趣" },
            clock, 3, 10)

    private TriggerEventDO pendingTrigger(long id, String dedupKey) {
        def trigger = new TriggerEventDO()
        trigger.setId(id)
        trigger.setPlatform("qq")
        trigger.setBotId("10000")
        trigger.setGroupId("80001")
        trigger.setDedupKey(dedupKey)
        trigger.setEventType("group_message")
        trigger.setDigest("消息内容")
        trigger.setDecision(TriggerEventDO.DECISION_PENDING)
        trigger.setOccurredAt(LocalDateTime.now(clock))
        return trigger
    }

    def "同群积压触发器合并一次模型调用 批量决策落档"() {
        given: "五条 pending 触发器，剧本只接受其中一条"
        def pending = (1..5).collect { pendingTrigger(it, "msg-" + it) }
        mapper.selectPendingByGroup("qq", "10000", "80001") >> pending
        assessor.scriptAccept("msg-3", "话题相关且有增量")

        when:
        service.assessGroup("qq", "10000", "80001")

        then: "一次调用批量判定"
        assessor.callCount == 1
        1 * mapper.updateDecision(3L, "accept", "L1", "话题相关且有增量", _, _)
        4 * mapper.updateDecision(_, "ignore", "L1", _, _, _)
    }

    def "评估失败指数退避重试 重试成功后正常落档"() {
        given: "前两次失败后成功"
        mapper.selectPendingByGroup("qq", "10000", "80001") >> [pendingTrigger(1L, "msg-1")]
        assessor.failFirstThenSuccess(2)

        when:
        service.assessGroup("qq", "10000", "80001")

        then:
        assessor.callCount == 3
        1 * mapper.updateDecision(1L, "ignore", "L1", _, _, _)
    }

    def "重试耗尽保守ignore 且 decision_layer=degrade"() {
        given: "持续失败"
        mapper.selectPendingByGroup("qq", "10000", "80001") >> [pendingTrigger(1L, "msg-1"), pendingTrigger(2L, "msg-2")]
        assessor.alwaysFail()

        when:
        service.assessGroup("qq", "10000", "80001")

        then: "初始 1 次 + 重试 3 次 = 4 次调用后降级"
        assessor.callCount == 4
        2 * mapper.updateDecision(_, "ignore", "degrade", { it.contains("保守") }, _, _)
    }

    def "无 pending 触发器时不调用模型"() {
        given:
        mapper.selectPendingByGroup("qq", "10000", "80001") >> []

        when:
        service.assessGroup("qq", "10000", "80001")

        then:
        assessor.callCount == 0
        0 * mapper.updateDecision(_, _, _, _, _, _)
    }
}
