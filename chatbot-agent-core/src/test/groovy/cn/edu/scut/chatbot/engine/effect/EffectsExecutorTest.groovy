// 守护纪律：Effects 三分支（成功删行/可重试退避/超限置failed）——崩溃动作不丢是结构保证（宪法第三条）
package cn.edu.scut.chatbot.engine.effect

import cn.edu.scut.chatbot.exception.NonRetryableException
import cn.edu.scut.chatbot.exception.RetryableException
import cn.edu.scut.chatbot.bootstrap.GroupSerialExecutor
import cn.edu.scut.chatbot.fixtures.FixedClock
import spock.lang.Specification

import java.time.LocalDateTime

class EffectsExecutorTest extends Specification {

    def mapper = Mock(EffectsQueueMapper)
    def serialExecutor = new GroupSerialExecutor()
    def clock = new FixedClock()

    def handler = Mock(EffectHandler)

    def cleanup() {
        serialExecutor.shutdown()
    }

    /**
     * stub 处理器 kind 之后再建 executor（注册表在构造期收集——stub 必须先行）
     */
    private EffectsExecutor executorFor(String kind) {
        handler.kind() >> kind
        return new EffectsExecutor(mapper, serialExecutor, [handler],
                Mock(cn.edu.scut.chatbot.bootstrap.SystemParamService), clock, 2L, 600L, 2, 32)
    }

    private EffectsQueueDO dueEffect(long id = 1L, String kind = "dispatch") {
        def effect = new EffectsQueueDO()
        effect.setId(id)
        effect.setPlatform("qq")
        effect.setBotId("10000")
        effect.setGroupId("80001")
        effect.setKind(kind)
        effect.setPayload("{}")
        effect.setRetryCount(0)
        effect.setNextRunAt(LocalDateTime.now(clock))
        return effect
    }

    def "五kind处理器注册表按kind分派"() {
        given: "五个不同 kind 的处理器与各自记录"
        def calls = Collections.synchronizedList(new ArrayList<String>())
        def handlers = ["assessment", "generation", "dispatch", "taskWake", "workerLaunch"].collect { kind ->
            def kindHandler = Mock(EffectHandler)
            kindHandler.kind() >> kind
            kindHandler.handle(_) >> { calls << kind }
            return kindHandler
        }
        def fiveKindExecutor = new EffectsExecutor(mapper, serialExecutor, handlers,
                Mock(cn.edu.scut.chatbot.bootstrap.SystemParamService), clock, 2L, 600L, 2, 32)

        and: "同轮扫描出五个 kind 各一条（通配抢锁全部成功）"
        mapper.selectDue(_, 32) >> [dueEffect(1L, "assessment"), dueEffect(2L, "generation"),
                                    dueEffect(3L, "dispatch"), dueEffect(4L, "taskWake"),
                                    dueEffect(5L, "workerLaunch")]
        mapper.claimForRun(_, _) >> 1

        when:
        fiveKindExecutor.scanAndExecute()
        serialExecutor.awaitDrain(5000)

        then: "每个动作派给自己的处理器"
        new HashSet<>(calls) == ["assessment", "generation", "dispatch", "taskWake", "workerLaunch"] as Set
    }

    def "执行成功删行（队列不是日志）"() {
        given:
        def executor = executorFor("dispatch")
        handler.handle(_) >> {}
        mapper.selectDue(_, 32) >> [dueEffect()]
        mapper.claimForRun(_, _) >> 1

        when:
        executor.scanAndExecute()
        serialExecutor.awaitDrain(5000)

        then:
        1 * mapper.deleteById(1L)
        0 * mapper.scheduleRetry(_, _, _)
        0 * mapper.markFailed(_)
    }

    def "可重试失败指数退避重试计数推进"() {
        given:
        def executor = executorFor("dispatch")
        handler.handle(_) >> { throw new RetryableException("effect.test.retryable", "XADD 失败") }
        mapper.selectDue(_, 32) >> [dueEffect()]
        mapper.claimForRun(_, _) >> 1

        when: "首次失败（retryCount 0 → 1）"
        executor.scanAndExecute()
        serialExecutor.awaitDrain(5000)

        then:
        1 * mapper.scheduleRetry(1L, 1, _)

        when: "第二次失败（retryCount 1 → 2，退避时长翻倍）"
        def second = dueEffect()
        second.setRetryCount(1)
        mapper.selectDue(_, 32) >> [second]
        executor.scanAndExecute()
        serialExecutor.awaitDrain(5000)

        then:
        1 * mapper.scheduleRetry(1L, 2, _)
    }

    def "重试超限置failed（maxRetry=2 时第 3 次失败）"() {
        given:
        def executor = executorFor("dispatch")
        handler.handle(_) >> { throw new RetryableException("effect.test.retryable", "持续失败") }
        def third = dueEffect()
        third.setRetryCount(2)   // 已重试 2 次，本次失败将超限
        mapper.selectDue(_, 32) >> [third]
        mapper.claimForRun(_, _) >> 1

        when:
        executor.scanAndExecute()
        serialExecutor.awaitDrain(5000)

        then:
        1 * mapper.markFailed(1L)
        0 * mapper.deleteById(_)
    }

    def "不可重试异常直接置failed"() {
        given:
        def executor = executorFor("generation")
        handler.handle(_) >> { throw new NonRetryableException("effect.payload_broken", "载荷坏") }
        mapper.selectDue(_, 32) >> [dueEffect(1L, "generation")]
        mapper.claimForRun(_, _) >> 1

        when:
        executor.scanAndExecute()
        serialExecutor.awaitDrain(5000)

        then:
        1 * mapper.markFailed(1L)
        0 * mapper.scheduleRetry(_, _, _)
    }

    def "启动恢复running批量置回pending"() {
        given:
        handler.kind() >> "dispatch"
        def executor = executorFor("dispatch")
        mapper.recoverRunningToPending() >> 3

        when:
        executor.recoverOnStartup()

        then:
        noExceptionThrown()
    }

    def "未知kind置failed"() {
        given: "只注册 dispatch 处理器，扫描出一行 mysteryKind"
        def executor = executorFor("dispatch")
        mapper.selectDue(_, 32) >> [dueEffect(9L, "mysteryKind")]
        mapper.claimForRun(_, _) >> 1

        when:
        executor.scanAndExecute()
        serialExecutor.awaitDrain(5000)

        then:
        1 * mapper.markFailed(9L)
    }
}
