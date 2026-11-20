package cn.edu.scut.chatbot.access.infrastructure

import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 守护纪律：同群严格串行、异群并行（宪法第四条，出流串行由接入层 Sender 保证）。
 * (botId, groupId) 是串行键：同群任务按提交顺序完成、执行不交叉；异群任务互不等待。
 * 并发正确性用可观察结果顺序验证（完成顺序 / 闸门不交叉），不用调度时序断言。
 */
class GroupSerialExecutorTest extends Specification {

    def executor = new GroupSerialExecutor()

    def cleanup() {
        executor.shutdown()
    }

    def "同群任务按提交顺序串行完成"() {
        given: "记录完成顺序的结果列表"
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<String>())

        when: "同一群提交 50 个任务，每个完成后登记自己的序号"
        def futures = (1..50).collect { sequence ->
            executor.submit("10000", "123456789", null, () -> completionOrder.add("task-" + sequence))
        }
        CompletableFuture.allOf(*futures).get(30, TimeUnit.SECONDS)

        then: "完成顺序与提交顺序一致（严格串行，无交叉）"
        completionOrder == (1..50).collect { "task-" + it }
    }

    def "同群任务执行不交叉——前序任务持有闸门时后序任务不开工"() {
        given: "第一个任务持有闸门不放，第二个任务若与它交叉就会同时通过闸门"
        def firstTaskStarted = new CountDownLatch(1)
        def releaseFirstTask = new CountDownLatch(1)
        def secondTaskEnteredWhileFirstRunning = new AtomicBoolean(false)

        def first = executor.submit("10000", "111", null, {
            firstTaskStarted.countDown()
            releaseFirstTask.await(10, TimeUnit.SECONDS)
        })
        def second = executor.submit("10000", "111", null, {
            // 能执行到这里说明第一个已让出——若第一个还持有闸门（未 release），即发生交叉
            secondTaskEnteredWhileFirstRunning.set(firstTaskStarted.count == 0 && releaseFirstTask.count == 1)
        })

        firstTaskStarted.await(10, TimeUnit.SECONDS)
        when: "放行第一个任务"
        releaseFirstTask.countDown()
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS)

        then: "第二个任务开始时第一个必须已结束（闸门计数归零）"
        secondTaskEnteredWhileFirstRunning.get() == false
    }

    def "异群任务并行执行——两群总耗时约等于单任务而非两倍"() {
        given: "每个任务睡 300ms，闸门保证同群内互斥"
        def start = System.nanoTime()

        when: "两个不同群各提交一个 300ms 任务"
        def groupA = executor.submit("10000", "111", null, { Thread.sleep(300) })
        def groupB = executor.submit("10000", "222", null, { Thread.sleep(300) })
        CompletableFuture.allOf(groupA, groupB).get(10, TimeUnit.SECONDS)

        then: "总耗时应接近 300ms（并行），远小于串行的 600ms"
        def elapsedMillis = (System.nanoTime() - start) / 1_000_000
        elapsedMillis < 550
    }

    def "groupId为空时以userId作串行键不同用户可并行"() {
        when: "两个无群事件（不同 userId）互不阻塞"
        def futures = [
                executor.submit("10000", null, "user-1", () -> Thread.sleep(200)),
                executor.submit("10000", null, "user-2", () -> Thread.sleep(200))
        ]
        def start = System.nanoTime()
        CompletableFuture.allOf(*futures).get(10, TimeUnit.SECONDS)

        then:
        (System.nanoTime() - start) / 1_000_000 < 550
    }

    def "groupId与userId均为空时以全局串行键兜底任务仍被执行"() {
        given: "全局事件（无群无用户维度）——形态完备性预留分支"
        def executed = new CountDownLatch(1)

        when: "提交一个空维度任务"
        def future = executor.submit("10000", null, null, () -> executed.countDown())

        then: "有键可分（__global__），任务正常执行完成"
        executed.await(10, TimeUnit.SECONDS)
        future.get(10, TimeUnit.SECONDS) == null
        executor.activeSerialKeyCount() >= 1
    }

    def "groupId与userId为空白字符串时同样落到全局串行键"() {
        given:
        def executed = new CountDownLatch(2)

        when: "空白串与 null 等价——都路由到 __global__ 同一条泵"
        def futures = [
                executor.submit("10000", "  ", "", () -> executed.countDown()),
                executor.submit("10000", null, null, () -> executed.countDown())
        ]

        then: "同一条全局泵串行执行两个任务，各自完成"
        executed.await(10, TimeUnit.SECONDS)
        futures.every { it.get(10, TimeUnit.SECONDS) == null }
    }

    def "任务抛异常不中断泵后续同群任务照常执行"() {
        given: "第一个任务抛运行时异常（投递任务的 bug 路径）"
        def secondExecuted = new CountDownLatch(1)

        when:
        def failing = executor.submit("10000", "333", null, { throw new IllegalStateException("任务自身 bug") })
        def following = executor.submit("10000", "333", null, () -> secondExecuted.countDown())

        then: "异常任务的 future 以异常完成（不静默）"
        def failureError = failing.handle { result, error -> error }.get(10, TimeUnit.SECONDS)
        failureError instanceof IllegalStateException

                and : "后续任务不受牵连，泵继续工作"
        secondExecuted.await(10, TimeUnit.SECONDS)
        following.get(10, TimeUnit.SECONDS) == null
    }

    def "同groupId不同botId是不同泵互不阻塞"() {
        when: "两个 bot 的同一群号各自投递——串行键含 botId，互不等待"
        def start = System.nanoTime()
        def futures = [
                executor.submit("10000", "444", null, () -> Thread.sleep(200)),
                executor.submit("20000", "444", null, () -> Thread.sleep(200))
        ]
        CompletableFuture.allOf(*futures).get(10, TimeUnit.SECONDS)

        then:
        (System.nanoTime() - start) / 1_000_000 < 550
    }

    def "awaitDrain在全部任务完成后返回true"() {
        given:
        def futures = (1..10).collect { sequence ->
            executor.submit("10000", "555", null, { Thread.sleep(20) })
        }

        when:
        CompletableFuture.allOf(*futures).get(10, TimeUnit.SECONDS)

        then: "优雅停机的排空口径：已入队 + 正在执行的计数归零"
        executor.awaitDrain(5000)
    }

    def "awaitDrain在任务未完成时超时返回false"() {
        given: "一个持有闸门不放的长任务"
        def release = new CountDownLatch(1)
        executor.submit("10000", "666", null, { release.await(10, TimeUnit.SECONDS) })

        when: "只给 200ms 排空窗口"
        def drained = executor.awaitDrain(200)

        then: "未排空返回 false（停机方决定是否继续等）"
        !drained

        cleanup:
        release.countDown()
    }
}
