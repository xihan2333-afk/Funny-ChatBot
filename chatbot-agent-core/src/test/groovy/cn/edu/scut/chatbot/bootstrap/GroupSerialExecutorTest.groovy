// 守护纪律：同群串行是数据一致性的前提（宪法第四条：同群事件严格串行、异群并行）
package cn.edu.scut.chatbot.bootstrap

import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GroupSerialExecutorTest extends Specification {

    def executor = new GroupSerialExecutor()

    def cleanup() {
        executor.shutdown()
    }

    def "同群任务按提交顺序串行执行"() {
        given: "100 个任务提交到同一群"
        def executedOrder = new CopyOnWriteArrayList<Integer>()

        when:
        (1..100).each { seq -> executor.submit("bot-1", "group-1", { -> executedOrder << seq }) }
        executor.awaitDrain(5000)

        then: "执行顺序与提交顺序完全一致"
        executedOrder == (1..100).toList()
    }

    def "异群任务互不阻塞并行执行"() {
        given: "群 A 有一个长任务，群 B 的短任务在其间提交"
        def groupAStarted = new CountDownLatch(1)
        def groupBDone = new CountDownLatch(1)

        when:
        executor.submit("bot-1", "group-A", {
            groupAStarted.countDown()
            groupBDone.await()      // 群 A 等群 B 完成——若两群被串到一条泵上这里会死锁
        })
        groupAStarted.await()
        def futureB = executor.submit("bot-1", "group-B", { -> groupBDone.countDown() })
        futureB.get(5, TimeUnit.SECONDS)

        then: "群 B 不被群 A 阻塞（异群并行）"
        groupBDone.count == 0
    }

    def "任务抛异常不中断泵 后续任务照常执行"() {
        given:
        def results = new CopyOnWriteArrayList<String>()

        when:
        executor.submit("bot-1", "group-1", { -> throw new IllegalStateException("任务自身 bug") })
        executor.submit("bot-1", "group-1", { -> results << "second" })
        executor.awaitDrain(5000)

        then:
        results == ["second"]
    }
}
