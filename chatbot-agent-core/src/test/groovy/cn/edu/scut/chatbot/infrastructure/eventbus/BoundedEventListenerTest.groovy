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
        def event = LoopEvent.RunEvent.started(1L, RunConfig.ExecutionForm.TURN)

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
        (1..3).each { listener.onEvent(LoopEvent.RunEvent.started(it as long, RunConfig.ExecutionForm.TURN)) }

        then:
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until({ received.size() == 3 })
    }
}
