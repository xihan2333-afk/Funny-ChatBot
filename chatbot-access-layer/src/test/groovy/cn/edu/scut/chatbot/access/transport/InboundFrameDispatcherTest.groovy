package cn.edu.scut.chatbot.access.transport

import cn.edu.scut.chatbot.access.codec.InboundPipeline
import spock.lang.Specification

/**
 * 守护纪律：入站文本帧三路分发（契约 onebot-ws-adapter.md 第 2 节 + TDD 任务 T024）。
 * 半截帧不丢（走兜底管线留档）、无主 API 响应不炸进程（留档 + 警告丢弃）、
 * 平台事件不与 API 响应混流（含 echo 与否是唯一判据）。
 * 单元测试 Mock 一切协作者（宪法第七条）。
 */
class InboundFrameDispatcherTest extends Specification {

    def apiResponseRouter = Mock(ApiResponseRouter)
    def inboundPipeline = Mock(InboundPipeline)
    def dispatcher = new InboundFrameDispatcher(apiResponseRouter, inboundPipeline)

    def "半截畸形帧走兜底管线原文透传"() {
        given: "非法 JSON 的半截帧"
        def brokenFrame = '{"time": 1717200000, "self_id": 10'

        when:
        dispatcher.dispatch(brokenFrame)

        then: "原文一字不改交给兜底管线（留档 + illegal_json 兜底由管线负责）"
        1 * inboundPipeline.handleUnparseableFrame(brokenFrame)
        0 * inboundPipeline.handlePlatformEvent(_, _)
        0 * apiResponseRouter.route(_)
    }

    def "合法JSON但不是对象的帧同样走兜底管线"() {
        when: "字符串 / 数组 / 数字 / null 字面量——都不是事件对象"
        dispatcher.dispatch('"just text"')
        dispatcher.dispatch('[1, 2, 3]')
        dispatcher.dispatch('123')

        then:
        3 * inboundPipeline.handleUnparseableFrame(_)
        0 * inboundPipeline.handlePlatformEvent(_, _)
    }

    def "含echo且命中待匹配表的帧只走API响应路由不进事件管线"() {
        given:
        def responseFrame = '{"status": "ok", "retcode": 0, "echo": "echo-1", "data": {}}'

        when:
        dispatcher.dispatch(responseFrame)

        then: "断言自带响应——then 裸断言会遮蔽 given 的 stub（同 PendingRescannerTest 经验）"
        1 * apiResponseRouter.route(_) >> true
        0 * inboundPipeline._
    }

    def "含echo但不命中待匹配表的帧按无主响应留档丢弃"() {
        given: "迟到或陌生的 echo——没有等待方认领"
        apiResponseRouter.route(_) >> false

        when:
        dispatcher.dispatch('{"status": "ok", "retcode": 0, "echo": "echo-unknown"}')

        then: "留档后丢弃，不进事件管线（API 响应不是平台事件）"
        1 * inboundPipeline.handleOrphanApiResponse('{"status": "ok", "retcode": 0, "echo": "echo-unknown"}')
        0 * inboundPipeline.handlePlatformEvent(_, _)
    }

    def "echo字段为null的帧按平台事件处理"() {
        given: "部分协议端会给 echo 显式置 null 的事件帧"
        def eventFrame = '{"time": 1717200000, "post_type": "message", "echo": null}'

        when:
        dispatcher.dispatch(eventFrame)

        then:
        1 * inboundPipeline.handlePlatformEvent(eventFrame, { it.path("post_type").asText() == "message" })
        0 * apiResponseRouter.route(_)
    }

    def "不含echo的平台事件帧连同已解析节点进事件管线"() {
        given: "正常群消息事件帧"
        def eventFrame = '{"time": 1717200000, "self_id": 10000, "post_type": "message", "message_type": "group"}'

        when:
        dispatcher.dispatch(eventFrame)

        then: "已解析的 node 一并传入（管线不再二次解析）"
        1 * inboundPipeline.handlePlatformEvent(eventFrame, { node ->
            node.path("message_type").asText() == "group" && node.path("self_id").asLong() == 10000L
        })
        0 * apiResponseRouter.route(_)
    }
}
