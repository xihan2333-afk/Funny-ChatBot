package cn.edu.scut.chatbot.access.bootstrap

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException
import cn.edu.scut.chatbot.contract.error.ErrorCodes
import spock.lang.Specification

import java.time.Clock


/**
 * 守护纪律：进程外可见 ID 全部经自研雪花发号器（宪法「时间与 ID」节 + research.md R3）。
 * eventId 写入事件信封进 Redis Stream 与 Agent 侧，唯一性是链路追踪检索的硬需求；
 * 时钟回拨「宁可失败不可重复」（设计方案 3.4.5）。固定 / 可控 Clock 驱动全部场景，不依赖真实系统时钟。
 */
class SnowflakeIdGeneratorTest extends Specification {

    /** 自定义纪元（2026-01-01T00:00:00Z）后第 10 秒——测试时钟值必须落在纪元之后，否则时间戳位为负被误判回拨 */
    private static final long EPOCH_PLUS_10S = 1767225600000L + 10_000L

    def "同一毫秒内连续发号全部唯一且序列位递增"() {
        given: "固定时钟锁死在同一毫秒"
        def clock = Mock(Clock)
        clock.millis() >>> (1..200).collect { EPOCH_PLUS_10S }

        and: "机器号 2（接入层默认）"
        def generator = new SnowflakeIdGenerator(2, clock)

        when: "同毫秒内连续发 200 个号"
        def ids = (1..200).collect { generator.nextId() }

        then: "全部唯一"
        ids.size() == ids.toSet().size()

        and: "机器号位恒为 2、时间戳位恒定（同毫秒）、序列位覆盖 0..199"
        ids.every { id -> ((id >> 12) & 0x1F) == 2 }
        ids.every { id -> (id >> 17) == (EPOCH_PLUS_10S - 1767225600000L) }
        (ids.collect { it & 0xFFF } as Set) == (0L..199L).toSet()
    }

    def "跨毫秒发号序列归零且时间戳位前进"() {
        given: "时钟前进一步"
        def clock = Mock(Clock)
        clock.millis() >>> [EPOCH_PLUS_10S, EPOCH_PLUS_10S, EPOCH_PLUS_10S + 1]

        def generator = new SnowflakeIdGenerator(2, clock)

        when:
        def firstSameMillis = generator.nextId()
        def secondSameMillis = generator.nextId()
        def nextMillis = generator.nextId()

        then: "同毫秒内序列 0、1，跨毫秒后序列归零、时间戳位 +1"
        (firstSameMillis & 0xFFF) == 0
        (secondSameMillis & 0xFFF) == 1
        (nextMillis & 0xFFF) == 0
        (nextMillis >> 17) == (secondSameMillis >> 17) + 1
    }

    def "时钟回拨小于5毫秒时等待追上后继续发号"() {
        given: "第二次读钟回拨 3ms，等待后第三次读钟追回原时刻"
        def clock = Mock(Clock)
        clock.millis() >>> [EPOCH_PLUS_10S, EPOCH_PLUS_10S - 3, EPOCH_PLUS_10S, EPOCH_PLUS_10S]

        def generator = new SnowflakeIdGenerator(2, clock)

        when:
        def beforeBackward = generator.nextId()
        def afterRecovery = generator.nextId()

        then: "等待追上后正常发号，两个 ID 不同（序列位递增）"
        afterRecovery > beforeBackward
        (afterRecovery & 0xFFF) == (beforeBackward & 0xFFF) + 1
    }

    def "时钟回拨小于5毫秒但等待后仍未追上时拒绝发号"() {
        given: "回拨 3ms 且时钟停在回拨位置不动"
        def clock = Mock(Clock)
        clock.millis() >>> [EPOCH_PLUS_10S, EPOCH_PLUS_10S - 3, EPOCH_PLUS_10S - 3]

        def generator = new SnowflakeIdGenerator(2, clock)
        generator.nextId()

        when:
        generator.nextId()

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == ErrorCodes.ADAPTER_ID_CLOCK_BACKWARD
    }

    def "时钟回拨超过5毫秒直接拒绝发号不等待"() {
        given: "回拨 1000ms，远超容忍上限"
        def clock = Mock(Clock)
        clock.millis() >>> [EPOCH_PLUS_10S, EPOCH_PLUS_10S - 1000]

        def generator = new SnowflakeIdGenerator(2, clock)
        generator.nextId()

        when:
        generator.nextId()

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == ErrorCodes.ADAPTER_ID_CLOCK_BACKWARD
    }

    def "机器号越界时构造即拒绝"() {
        given:
        def clock = Mock(Clock)

        when:
        new SnowflakeIdGenerator(32, clock)

        then:
        thrown(IllegalArgumentException)

        when:
        new SnowflakeIdGenerator(-1, clock)

        then:
        thrown(IllegalArgumentException)
    }
}
