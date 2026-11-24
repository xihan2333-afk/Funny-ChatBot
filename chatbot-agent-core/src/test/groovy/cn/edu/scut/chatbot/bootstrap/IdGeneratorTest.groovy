// 守护纪律：进程外可见 ID 的唯一性（宪法「时间与 ID」节）——发放先于落库、宁可失败不可重复
package cn.edu.scut.chatbot.bootstrap

import cn.edu.scut.chatbot.exception.NonRetryableException
import cn.edu.scut.chatbot.fixtures.FixedClock
import spock.lang.Specification

class IdGeneratorTest extends Specification {

    def "同毫秒内连续发号严格递增且无重复"() {
        given: "固定时钟（时间静止，autoAdvance 默认关闭）"
        def clock = new FixedClock(1775001600000L)
        def generator = new IdGenerator(1, clock)

        when: "同毫秒内连发 100 个"
        def ids = (1..100).collect { generator.nextId() }

        then: "全部唯一且严格递增（单调性）"
        ids.size() == new HashSet<>(ids).size()
        ids == ids.sort(false)
    }

    def "时间前进后发号高位分量随之推进"() {
        given:
        def clock = new FixedClock(1775001600000L)
        def generator = new IdGenerator(1, clock)

        when:
        def first = generator.nextId()
        clock.advanceMillis(5)
        def second = generator.nextId()

        then: "时间戳在高位（左移 17 位），前进 5ms 后差值至少 5 << 17"
        (second - first) >= (5L << 17)
    }

    def "时钟回拨小于5ms 等待追上后继续发号"() {
        given: "已发一号（记下当前时刻），随后时钟回拨 3ms 并开启自动前进（模拟物理时钟恢复走动）"
        def clock = new FixedClock(1775001600000L)
        def generator = new IdGenerator(1, clock)
        generator.nextId()
        clock.advanceMillis(50)
        long before = clock.currentMillis()
        clock.setTo(before - 3)
        clock.setAutoAdvanceMillis(5)

        when: "回拨 3ms 后发号——等待后重读时钟已前进 5ms 越过原时刻"
        def id = generator.nextId()

        then: "追上后正常发号，不抛异常"
        id > 0
    }

    def "时钟回拨超过5ms 拒绝发号抛NonRetryableException"() {
        given:
        def clock = new FixedClock(1775001600000L)
        def generator = new IdGenerator(1, clock)
        clock.advanceMillis(100)
        generator.nextId()   // lastTimestamp = t0+100

        when: "时钟回拨到 t0+50（比上次发号时刻早 50ms）"
        clock.setTo(clock.currentMillis() - 50)
        generator.nextId()

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == "engine.id.clock_backward"
    }

    def "时钟回拨小于5ms 但等待后仍未追上同样拒绝发号"() {
        given: "上次发号在 t0+100，时钟回拨 3ms 且静止（等待后读到的仍是旧值）"
        def clock = new FixedClock(1775001600000L)
        def generator = new IdGenerator(1, clock)
        clock.advanceMillis(100)
        generator.nextId()
        clock.setTo(clock.currentMillis() - 3)

        when:
        generator.nextId()

        then:
        thrown(NonRetryableException)
    }
}
