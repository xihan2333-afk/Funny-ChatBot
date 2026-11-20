package cn.edu.scut.chatbot.access.infrastructure

import cn.edu.scut.chatbot.access.sender.OutboundConsumer
import spock.lang.Specification

/**
 * 守护纪律：至少一次 + 崩溃恢复（宪法第三条「进程崩溃动作不丢是结构保证」+ redis-streams.md 第 4 节）。
 * XPENDING 出流 IDLE 60000 取超时空闲 → 逐条 XCLAIM → 走与正常消费同一条分发路径（R5 决策，
 * 不用 XAUTOCLAIM）；投递次数超限记 adapter.stream.poison 告警后仍重投——无死信队列，
 * 异常持续暴露给观测。
 */
class PendingRescannerTest extends Specification {

    def streamGateway = Mock(StreamGateway)
    def outboundConsumer = Mock(OutboundConsumer)
    def rescanner = new PendingRescanner(streamGateway, outboundConsumer,
            "chatbot:out:onebot-v11", "access-layer", "access-layer-1", 60000, 5)

    def "空闲超阈值的pending消息经XCLAIM认领后走正常分发路径"() {
        given: "一条空闲 90 秒（超过 60 秒阈值）的 pending 消息"
        streamGateway.pendingIdleBeyond("chatbot:out:onebot-v11", "access-layer", 60000, 64) >> [
                new StreamGateway.PendingMessageView("10-0", 90_000, 2)
        ]
        and: "XCLAIM 取回消息体"
        streamGateway.claimIdle("chatbot:out:onebot-v11", "access-layer", "access-layer-1", 60000, ["10-0"]) >> [
                new StreamGateway.StreamMessage("10-0", '{"type":"send_group_message"}')
        ]

        when:
        rescanner.rescan()

        then: "XCLAIM 以空闲阈值作 minIdle 参数（两步命令参数正确）"
        1 * streamGateway.claimIdle("chatbot:out:onebot-v11", "access-layer", "access-layer-1",
                60000, ["10-0"]) >> [new StreamGateway.StreamMessage("10-0", '{"type":"send_group_message"}')]

        and: "认领到的消息走与正常消费同一条分发路径（不是独立重投逻辑）"
        1 * outboundConsumer.redispatch(new StreamGateway.StreamMessage("10-0", '{"type":"send_group_message"}'))
    }

    def "空闲未达阈值的消息不会被扫描到"() {
        given: "空闲阈值筛选在 XPENDING 侧完成——网关只回空闲超过阈值的消息"
        streamGateway.pendingIdleBeyond(_, "access-layer", 60000, 64) >> []

        when:
        rescanner.rescan()

        then:
        0 * streamGateway.claimIdle(_, _, _, _, _)
        0 * outboundConsumer.redispatch(_)
    }

    def "投递次数超五次记poison告警但仍认领重投"() {
        given: "空闲 90 秒且已投递 7 次的 pending 消息（疑似毒消息）"
        streamGateway.pendingIdleBeyond(_, "access-layer", 60000, 64) >> [
                new StreamGateway.PendingMessageView("11-0", 90_000, 7)
        ]

        when:
        rescanner.rescan()

        then: "告警后仍重投（无死信队列）——认领断言自带响应（then 的裸断言会遮蔽 given 的 stub）"
        1 * streamGateway.claimIdle(_, _, _, _, ["11-0"]) >> [
                new StreamGateway.StreamMessage("11-0", '{"type":"send_group_message"}')
        ]
        1 * outboundConsumer.redispatch(_)
    }

    def "单条认领失败不阻断同批其余消息"() {
        given: "两条超时空闲消息，第一条 XCLAIM 抛异常"
        streamGateway.pendingIdleBeyond(_, "access-layer", 60000, 64) >> [
                new StreamGateway.PendingMessageView("10-0", 90_000, 1),
                new StreamGateway.PendingMessageView("12-0", 70_000, 1)
        ]
        streamGateway.claimIdle(_, _, _, _, ["10-0"]) >> { throw new IllegalStateException("redis 抖动") }
        streamGateway.claimIdle(_, _, _, _, ["12-0"]) >> [
                new StreamGateway.StreamMessage("12-0", '{"type":"recall_message"}')
        ]

        when:
        rescanner.rescan()

        then: "第二条照常认领重投"
        1 * outboundConsumer.redispatch(new StreamGateway.StreamMessage("12-0", '{"type":"recall_message"}'))
    }

    def "XPENDING扫描失败时本轮放弃不抛异常"() {
        given: "Redis 不可达"
        streamGateway.pendingIdleBeyond(_, _, _, _) >> { throw new IllegalStateException("connection refused") }

        when:
        rescanner.rescan()

        then: "本轮放弃（下一轮周期再试），不 XCLAIM 不重投也不炸调度线程"
        noExceptionThrown()
        0 * streamGateway.claimIdle(_, _, _, _, _)
        0 * outboundConsumer.redispatch(_)
    }

    def "XCLAIM认领结果为空时不重投不抛异常"() {
        given: "消息已被其他消费者抢先认领或已删除——XCLAIM 返回空"
        streamGateway.pendingIdleBeyond(_, "access-layer", 60000, 64) >> [
                new StreamGateway.PendingMessageView("10-0", 90_000, 2)
        ]
        streamGateway.claimIdle(_, _, _, _, ["10-0"]) >> List.of()

        when:
        rescanner.rescan()

        then:
        noExceptionThrown()
        0 * outboundConsumer.redispatch(_)
    }

    def "投递次数恰好达到告警线仍照常重投"() {
        given: "投递 5 次（等于告警线 5，未超过）的 pending 消息"
        streamGateway.pendingIdleBeyond(_, "access-layer", 60000, 64) >> [
                new StreamGateway.PendingMessageView("13-0", 90_000, 5)
        ]
        streamGateway.claimIdle(_, _, _, _, ["13-0"]) >> [
                new StreamGateway.StreamMessage("13-0", '{"type":"send_group_message"}')
        ]

        when:
        rescanner.rescan()

        then: "边界取「超过才告警」，达到线上的消息继续正常重投"
        1 * outboundConsumer.redispatch(new StreamGateway.StreamMessage("13-0", '{"type":"send_group_message"}'))
    }
}
