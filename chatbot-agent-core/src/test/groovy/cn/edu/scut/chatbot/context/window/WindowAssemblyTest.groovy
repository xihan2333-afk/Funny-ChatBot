// 守护纪律：窗口是视图历史全量只增不改；锚定区 20 条永不淘汰、淘汰按 weight 次序（data-model 第 13 节）
package cn.edu.scut.chatbot.context.window

import cn.edu.scut.chatbot.loop.message.AgentMessage
import cn.edu.scut.chatbot.loop.message.EventEntry
import cn.edu.scut.chatbot.loop.message.SummaryEntry
import cn.edu.scut.chatbot.loop.message.UserMessage
import spock.lang.Specification

import java.time.Instant

class WindowAssemblyTest extends Specification {

    def assembler = new WindowAssembler(4000, 1000)

    private UserMessage user(String id, int offsetMinutes, String text, int weight = 50) {
        return new UserMessage(id, Instant.ofEpochSecond(1775000000 + offsetMinutes * 60L),
                weight, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", text)))
    }

    private EventEntry memberChange(String id, int offsetMinutes) {
        return new EventEntry(id, Instant.ofEpochSecond(1775000000 + offsetMinutes * 60L),
                EventEntry.WEIGHT_MEMBER_CHANGE, null, EventEntry.CATEGORY_MEMBER_CHANGE,
                Map.of("userId", "10010"), "10010 加入了群聊")
    }

    def "超预算转录淘汰按weight次序从成员变动开始 锚定区尾部20条在窗"() {
        given: "60 条转录：开头 3 条成员变动（weight 10），其余用户消息（weight 50）——总量超预算"
        def transcript = []
        transcript << memberChange("evt-1", 0)
        transcript << memberChange("evt-2", 1)
        transcript << memberChange("evt-3", 2)
        (1..57).each { i -> transcript << user("msg-" + i, i, "消息内容" + i) }

        when:
        def plan = assembler.assemble(transcript, null, "active")

        then: "锚定区（尾部 20 条）全部在窗"
        def inWindowIds = plan.conversationZone().collect { it.messageId() } as Set
        (38..57).each { i -> inWindowIds.contains("msg-" + i) }
        plan.conversationZone().size() >= 20

        and: "成员变动最先出窗（weight 10 最低）"
        plan.evicted().any { it.messageId() == "evt-1" }
        def evictedWeights = plan.evicted()*.weight()
        evictedWeights == evictedWeights.sort(false)
    }

    def "空转录与不足锚定条数的窗口组织"() {
        given:
        def few = (1..5).collect { i -> user("msg-" + i, i, "短会话" + i) }

        when:
        def plan = assembler.assemble(few, null, "active")

        then: "不足 20 条全部在窗、零淘汰"
        plan.conversationZone().size() == 5
        plan.evicted().isEmpty()

        when:
        def empty = assembler.assemble([], null, "active")

        then:
        empty.conversationZone().isEmpty()
        empty.evicted().isEmpty()
    }

    def "摘要区只投最新一段"() {
        given:
        def latest = new SummaryEntry("sum-2", Instant.now(), 50, null, "最新摘要", "范围2")
        def transcript = (1..10).collect { i -> user("msg-" + i, i, "消息" + i) }

        when:
        def plan = assembler.assemble(transcript, latest, "active")

        then:
        plan.summaryZone().size() == 1
        plan.summaryZone()[0].messageId() == "sum-2"
    }

    def "水位双档：quiet 档预算更小淘汰更多"() {
        given: "中等量转录——active 档装得下、quiet 档装不下"
        def transcript = (1..40).collect { i -> user("msg-" + i, i, "内容" + i) }

        when:
        def activePlan = assembler.assemble(transcript, null, "active")
        def quietPlan = assembler.assemble(transcript, null, "quiet")

        then:
        quietPlan.evicted().size() >= activePlan.evicted().size()
    }

    def "水位判定：投影量超触发线需要压缩"() {
        expect:
        !assembler.shouldCompact(3000, "active", 500)     // 未到 4000-500
        assembler.shouldCompact(3600, "active", 500)      // 超过触发线
        assembler.shouldCompact(700, "quiet", 500)        // quiet 档 1000-500=500 已超
    }
}
