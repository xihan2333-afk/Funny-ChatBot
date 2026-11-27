// 守护纪律：监听器只做快速入队、异常不外溢（契约 loop-kernel.md 第 3 节——结构保证非自觉）
package cn.edu.scut.chatbot.infrastructure.eventbus

import cn.edu.scut.chatbot.loop.RunConfig
import cn.edu.scut.chatbot.loop.events.LoopEvent
import spock.lang.Specification

class BoundedEventListenerTest extends Specification {

    def listener = new BoundedEventListener()

    def "队列满则丢弃并计数 绝不阻塞调用方"() {
        given: "不启后台消费（队列只涨——不依赖消费时序的确定性形态）"
        def quietListener = new BoundedEventListener(false)
        def event = LoopEvent.RunEvent.started(1L, RunConfig.ExecutionForm.TURN, null, null, false)

        when: "灌满队列容量 4096 再多灌 100 条"
        (1..4096).each { quietListener.onEvent(event) }
        (1..100).each { quietListener.onEvent(event) }

        then: "多出的 100 条被丢弃计数，主流程不被阻塞（onEvent 全部立即返回）"
        quietListener.droppedCount() == 100
    }

    def "后台消费者抛异常不外溢 不影响后续事件消化"() {
        given: "一个总抛异常的消费者与一个正常计数的消费者"
        def received = []
        listener.addBackgroundConsumer({ e -> throw new IllegalStateException("消费者炸了") })
        listener.addBackgroundConsumer({ e -> received << e })

        when: "入队 3 个事件等后台消化"
        (1..3).each { listener.onEvent(LoopEvent.RunEvent.started(it as long, RunConfig.ExecutionForm.TURN, null, null, false)) }

        then:
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until({ received.size() == 3 })
    }

    def "close前入队的残余事件排空送达后才停机（一个Run一个监听器，不泄漏空转线程）"() {
        given:
        def received = []
        listener.addBackgroundConsumer({ e -> received << e })

        when: "入队 5 个事件后立即关闭（关闭与消费并发——残余事件也要尽量送达）"
        (1..5).each { listener.onEvent(LoopEvent.RunEvent.started(it as long, RunConfig.ExecutionForm.TURN, null, null, false)) }
        listener.close()

        then: "关闭转入非阻塞排空模式：残余送完才退出"
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until({ listener.deliveredCount() == 5L })
        received.size() == 5
    }

    def "排空停机后再入队的事件不再消费（停机即停口）"() {
        given:
        def received = []
        listener.addBackgroundConsumer({ e -> received << e })
        (1..5).each { listener.onEvent(LoopEvent.RunEvent.started(it as long, RunConfig.ExecutionForm.TURN, null, null, false)) }
        listener.close()
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until({ listener.deliveredCount() == 5L })
        and: "交付数稳定一段时间——非阻塞排空模式下无残余即退出，不会驻留等待"
        org.awaitility.Awaitility.await().during(java.time.Duration.ofMillis(200))
                .atMost(java.time.Duration.ofSeconds(5))
                .until({ listener.deliveredCount() == 5L })

        when: "停机后再入队 3 个事件"
        (1..3).each { listener.onEvent(LoopEvent.RunEvent.started(it as long, RunConfig.ExecutionForm.TURN, null, null, false)) }

        then: "不被消费（已交付的保持 5 条）"
        received.size() == 5
        org.awaitility.Awaitility.await().during(java.time.Duration.ofMillis(300))
                .atMost(java.time.Duration.ofSeconds(5))
                .until({ listener.deliveredCount() == 5L })
    }

    def "无人注册消费者时close无害（幂等停机）"() {
        given:
        def quietListener = new BoundedEventListener(true)

        when:
        quietListener.close()
        quietListener.close()

        then:
        quietListener.droppedCount() == 0
        quietListener.deliveredCount() == 0
        notThrown(Exception)
    }
}
