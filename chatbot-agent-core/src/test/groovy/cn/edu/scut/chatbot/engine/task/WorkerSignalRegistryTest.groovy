// 守护纪律：取消只经 CancellationSignal 传入（不 interrupt、不持线程引用）；
// 无活跃 Worker 时置位无害（迟到取消不炸）
package cn.edu.scut.chatbot.engine.task

import cn.edu.scut.chatbot.loop.CancellationSignal
import spock.lang.Specification

class WorkerSignalRegistryTest extends Specification {

    def registry = new WorkerSignalRegistry()

    def "注册后的信号可被置位（生命周期事件经注册表找到在跑的 Worker）"() {
        given:
        def signal = CancellationSignal.create()
        registry.register(7L, signal)

        when:
        registry.cancelWorker(7L)

        then:
        signal.isCancelled()
    }

    def "注销后的信号不再被置位（Worker 已结束——迟到取消落空）"() {
        given:
        def signal = CancellationSignal.create()
        registry.register(7L, signal)
        registry.unregister(7L)

        when:
        registry.cancelWorker(7L)

        then:
        !signal.isCancelled()
    }

    def "无活跃Worker时置位无害（不抛异常）"() {
        when:
        registry.cancelWorker(404L)

        then:
        notThrown(Exception)
    }

    def "同Task重复注册以后注册者为准（重启恢复覆盖旧信号）"() {
        given:
        def first = CancellationSignal.create()
        def second = CancellationSignal.create()
        registry.register(7L, first)
        registry.register(7L, second)

        when:
        registry.cancelWorker(7L)

        then:
        second.isCancelled()
        !first.isCancelled()
    }

    def "不同Task的信号互不干扰"() {
        given:
        def signalA = CancellationSignal.create()
        def signalB = CancellationSignal.create()
        registry.register(1L, signalA)
        registry.register(2L, signalB)

        when:
        registry.cancelWorker(1L)

        then:
        signalA.isCancelled()
        !signalB.isCancelled()
    }
}
