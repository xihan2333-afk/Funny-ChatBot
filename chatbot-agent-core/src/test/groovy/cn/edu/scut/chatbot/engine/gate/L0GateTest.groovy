// 守护纪律：先评估后行动，绝大多数事件被安静忽略是正常状态（宪法第三条、spec US2）
package cn.edu.scut.chatbot.engine.gate

import spock.lang.Specification

class L0GateTest extends Specification {

    def gate = new L0Gate()

    def context(Map overrides = [:]) {
        def defaults = [
                eventType              : "group_message",
                senderUserId           : "10086",
                atBot                  : false,
                slashCommand           : false,
                botSelfMessage         : false,
                groupPaused            : false,
                userBlacklist          : [],
                cooldownRemainingMillis: 0L,
                sendCountInWindow      : 0,
                rateLimitPerMinute     : 6
        ]
        defaults.putAll(overrides)
        return new L0Gate.L0Context(
                defaults.eventType, defaults.senderUserId, defaults.atBot, defaults.slashCommand,
                defaults.botSelfMessage, defaults.groupPaused, defaults.userBlacklist,
                defaults.cooldownRemainingMillis, defaults.sendCountInWindow, defaults.rateLimitPerMinute)
    }

    def "被@提及强制接受 decision_layer=L0"() {
        when:
        def decision = gate.judge(context(atBot: true))

        then:
        decision.decision == "accept"
        decision.decisionLayer == "L0"
        decision.reason.contains("@")
    }

    def "斜杠指令强制接受"() {
        when:
        def decision = gate.judge(context(slashCommand: true))

        then:
        decision.decision == "accept"
        decision.decisionLayer == "L0"
    }

    def "黑名单用户即使@bot也强制忽略"() {
        when:
        def decision = gate.judge(context(atBot: true, userBlacklist: ["10086"]))

        then:
        decision.decision == "ignore"
        decision.reason.contains("黑名单")
    }

    def "冷却期内强制忽略"() {
        when:
        def decision = gate.judge(context(cooldownRemainingMillis: 5000L))

        then:
        decision.decision == "ignore"
        decision.reason.contains("冷却")
    }

    def "频率超限强制忽略"() {
        when:
        def decision = gate.judge(context(sendCountInWindow: 6, rateLimitPerMinute: 6))

        then:
        decision.decision == "ignore"
        decision.reason.contains("上限")
    }

    def "群暂停强制忽略（新触发器照常登记但不评估）"() {
        when:
        def decision = gate.judge(context(groupPaused: true))

        then:
        decision.decision == "ignore"
        decision.reason.contains("暂停")
    }

    def "Bot自身消息一律忽略（防自我激励）——即使携带@自己"() {
        when:
        def decision = gate.judge(context(botSelfMessage: true, atBot: true))

        then:
        decision.decision == "ignore"
        decision.reason.contains("自我激励")
    }

    def "普通消息无规则命中升级L1"() {
        when:
        def decision = gate.judge(context())

        then:
        decision.escalateToL1
        decision.decision == "pending"
    }
}
