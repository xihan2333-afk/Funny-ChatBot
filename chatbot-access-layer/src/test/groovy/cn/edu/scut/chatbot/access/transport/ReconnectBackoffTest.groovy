package cn.edu.scut.chatbot.access.transport

import spock.lang.Specification

/**
 * 守护纪律：断线恢复是结构保证（宪法第三条「进程崩溃动作不丢是结构保证」的接入侧配套：
 * 指数退避重连让接入层对协议端故障自愈；契约 onebot-ws-adapter.md 第 1 节）。
 * 纯计算类固定 Clock 独立可测——退避序列 1s 起步、×2 递增、60s 封顶、成功后重置。
 */
class ReconnectBackoffTest extends Specification {

    def backoff = new ReconnectBackoff(1000, 2, 60000)

    def "退避序列一秒起步每次翻倍直至六十秒封顶"() {
        when: "连续失败十一次"
        def delays = (1..11).collect { backoff.nextDelayMillis() }

        then: "序列为 1s 2s 4s 8s 16s 32s 60s 60s 60s…（翻倍且封顶）"
        delays == [1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L, 60000L, 60000L]
    }

    def "重连成功后退避序列重置回起步值"() {
        when: "失败三次后退避已到 8s"
        (1..3).collect { backoff.nextDelayMillis() }

        and: "连接成功触发重置"
        backoff.reset()

        then: "再次失败从 1s 重新起步"
        backoff.nextDelayMillis() == 1000L
        backoff.nextDelayMillis() == 2000L
    }

    def "失败次数可供鉴权失败判定复用"() {
        when:
        backoff.nextDelayMillis()
        backoff.nextDelayMillis()

        then:
        backoff.consecutiveFailures() == 2

        when:
        backoff.reset()

        then:
        backoff.consecutiveFailures() == 0
    }

    def "倍率为一时退避恒为起步值不递增"() {
        given: "倍率 1（固定间隔重连策略）"
        def constantBackoff = new ReconnectBackoff(5000, 1, 60000)

        when:
        def delays = (1..5).collect { constantBackoff.nextDelayMillis() }

        then:
        delays == [5000L, 5000L, 5000L, 5000L, 5000L]
    }

    def "起步值等于上限时全程封顶不越界"() {
        given: "起步即 60s、上限 60s 的配置"
        def pinnedBackoff = new ReconnectBackoff(60000, 2, 60000)

        when:
        def delays = (1..4).collect { pinnedBackoff.nextDelayMillis() }

        then:
        delays == [60000L, 60000L, 60000L, 60000L]
    }
}
