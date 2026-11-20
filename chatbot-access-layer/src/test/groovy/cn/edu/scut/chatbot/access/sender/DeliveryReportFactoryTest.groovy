package cn.edu.scut.chatbot.access.sender

import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator
import cn.edu.scut.chatbot.access.fixtures.SteadyClock
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.in.DeliveryFailureKindCodes
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload
import cn.edu.scut.chatbot.contract.in.DeliveryStatusCodes
import spock.lang.Specification

import java.time.ZoneOffset
import java.util.concurrent.TimeoutException

/**
 * 守护纪律：无「发出即忘」路径（宪法第三条：凭投递回执闭环结算）。
 * 每次发送（含失败 / 不支持）都产出 DeliveryReport 回流入流；失败分类是 Agent 行为调度的
 * 输入（content_rejected / network / media_missing / unknown 逐类映射），unsupported 不算 failed。
 * attemptedAt 由注入 Clock 打点——固定时钟断言。
 */
class DeliveryReportFactoryTest extends Specification {

    def clock = new SteadyClock(1775001600000L)
    def factory = new DeliveryReportFactory(
            new SnowflakeIdGenerator(2, clock), clock)

    def "成功回执携带outboxId与platformMessageId且attemptedAt来自固定时钟"() {
        when:
        def report = factory.success("3849281194743617536", "7654321")
        def envelope = factory.toEnvelope("onebot-v11", "10000", report, "00-trace-1")

        then:
        report.outboxId() == "3849281194743617536"
        report.platformMessageId() == "7654321"
        report.status() == DeliveryStatusCodes.SUCCESS
        report.failureKind() == null
        report.attemptedAt() == 1775001600000L
        report.retried() == false

        and: "回执作为入事件信封回流（type=delivery_report，traceId 沿用原出事件）"
        envelope.type() == PrimitiveTypeCodes.DELIVERY_REPORT
        envelope.outboxId() == "3849281194743617536"
        envelope.traceContext() == "00-trace-1"
        envelope.eventId() != null
        envelope.dedupKey() != null
    }

    def "审核类retcode映射content_rejected"() {
        expect:
        def report = factory.failed("out-1",
                DeliveryReportFactory.classifyRetcodeFailure(1403, "token验证失败"), "retcode=1403", false)
        report.status() == DeliveryStatusCodes.FAILED
        report.failureKind() == DeliveryFailureKindCodes.CONTENT_REJECTED
    }

    def "网络异常映射network且瞬时重试后失败标记retried"() {
        expect:
        def report = factory.failed("out-2",
                DeliveryFailureKindCodes.NETWORK, new TimeoutException("connect timeout").toString(), true)
        report.status() == DeliveryStatusCodes.FAILED
        report.failureKind() == DeliveryFailureKindCodes.NETWORK
        report.retried() == true
    }

    def "本地媒体缺失映射media_missing"() {
        expect:
        def report = factory.failed("out-3",
                DeliveryFailureKindCodes.MEDIA_MISSING, "storedRef 指向的文件不存在", false)
        report.failureKind() == DeliveryFailureKindCodes.MEDIA_MISSING
        report.platformMessageId() == null
    }

    def "其余失败映射unknown兜底"() {
        expect:
        def report = factory.failed("out-4",
                DeliveryReportFactory.classifyRetcodeFailure(999, "什么奇怪的错误"), "retcode=999", false)
        report.failureKind() == DeliveryFailureKindCodes.UNKNOWN
    }

    def "unsupported不算failed且携带detail"() {
        expect:
        def report = factory.unsupported("out-5", "当前协议端不支持 set_msg_emoji_like")
        report.status() == DeliveryStatusCodes.UNSUPPORTED
        report.failureKind() == null
        report.failureDetail() == "当前协议端不支持 set_msg_emoji_like"
    }

    def "failureDetail截断至512字符"() {
        given:
        def longDetail = "x" * 1000

        when:
        def report = factory.failed("out-6", DeliveryFailureKindCodes.UNKNOWN, longDetail, false)

        then:
        report.failureDetail().length() == DeliveryReportPayload.FAILURE_DETAIL_MAX_LENGTH
    }

    def "审核类retcode的12xx区间边界逐点判定"() {
        expect: "1200 与 1299 属审核风控区间内；1199 与 1300 在区间外走 unknown"
        DeliveryReportFactory.classifyRetcodeFailure(1200, "") == DeliveryFailureKindCodes.CONTENT_REJECTED
        DeliveryReportFactory.classifyRetcodeFailure(1299, "") == DeliveryFailureKindCodes.CONTENT_REJECTED
        DeliveryReportFactory.classifyRetcodeFailure(1199, "") == DeliveryFailureKindCodes.UNKNOWN
        DeliveryReportFactory.classifyRetcodeFailure(1300, "") == DeliveryFailureKindCodes.UNKNOWN
    }

    def "回执幂等键为32位十六进制内容哈希"() {
        when:
        def envelope = factory.toEnvelope("onebot-v11", "10000",
                factory.success("out-7", "7654321"), "00-trace-7")

        then:
        envelope.dedupKey().matches("[0-9a-f]{32}")
        envelope.dedupKey().length() <= 128
    }

    def "同outboxId不同尝试时刻的回执幂等键不同"() {
        given: "pending 重投会产生第二条同 outboxId 回执——Agent 按 (outboxId + attemptedAt) 取最新"
        def firstEnvelope = factory.toEnvelope("onebot-v11", "10000",
                factory.success("out-8", "1"), null)

        when: "时钟前进后第二次回执"
        clock.advanceMillis(1500)
        def secondEnvelope = factory.toEnvelope("onebot-v11", "10000",
                factory.success("out-8", "1"), null)

        then:
        firstEnvelope.dedupKey() != secondEnvelope.dedupKey()
        secondEnvelope.payload().attemptedAt() - firstEnvelope.payload().attemptedAt() == 1500L
    }
}
