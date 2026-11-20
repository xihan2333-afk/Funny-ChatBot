package cn.edu.scut.chatbot.access.archive

import cn.edu.scut.chatbot.access.fixtures.SteadyClock
import spock.lang.Specification

import java.time.LocalDateTime

/**
 * 守护纪律：原始报文留档不做任何加工——「信息不丢失」的最终落点（data-model.md 第 8 节 + T015/T017）。
 * raw_payload 全文原样落库、occurredAt/receivedAt 用注入 Clock 的时区换算、
 * 自增 id 回填后 rawRef.rawEventId 回指该行（信封 rawRef 回指契约的源头）。
 * 单元测试 Mock 一切协作者（宪法第七条）：mapper 是接口直接 Mock，useGeneratedKeys 行为以闭包模拟
 * （stub 响应挂在 then 断言上并捕获入参 DO——then 裸断言会遮蔽 given 的 stub，同 PendingRescannerTest 经验）。
 */
class RawEventArchiverTest extends Specification {

    def mapper = Mock(RawEventLogMapper)
    /** SteadyClock 时区 Asia/Shanghai：1775001600000ms = 2026-04-01T08:00（东八区） */
    def clock = new SteadyClock(1775001600000L)
    def archiver = new RawEventArchiver(mapper, clock)

    /** 模拟 useGeneratedKeys：断言响应闭包里回填自增 id 并捕获 DO 供后续字段断言 */
    private RawEventLogDO capturedRow = null

    private def insertOnceWithGeneratedKey(long generatedId) {
        1 * mapper.insertRawEventLog(_ as RawEventLogDO) >> { RawEventLogDO row ->
            capturedRow = row
            row.setId(generatedId)
            return 1
        }
    }

    def "留档全字段映射且rawRef回指回填后的自增id"() {
        given: "数据库回填自增 id=8821"
        def rawPayload = '{"time":1775001600,"self_id":10000,"post_type":"message",' +
                '"message_type":"group","message_id":1234567}'

        when: "留档一条群消息原文"
        def rawRef = archiver.archive("onebot-v11", "10000", RawEventArchiver.DIRECTION_IN,
                "message.group", "1234567", 1775001600000L, rawPayload)

        then:
        interaction { insertOnceWithGeneratedKey(8821L) }

        and: "rawRef 回指留档行（信封 rawRef 回指契约的源头）"
        rawRef.platform() == "onebot-v11"
        rawRef.rawEventId() == "8821"
        rawRef.payloadFormat() == "json"

        and: "DO 逐字段：原文一字不改、双时间戳按注入 Clock 的时区换算"
        capturedRow != null
        capturedRow.platform == "onebot-v11"
        capturedRow.botId == "10000"
        capturedRow.direction == "in"
        capturedRow.eventType == "message.group"
        capturedRow.dedupKey == "1234567"
        capturedRow.rawPayload == rawPayload
        capturedRow.occurredAt == LocalDateTime.of(2026, 4, 1, 8, 0, 0)
        capturedRow.receivedAt == LocalDateTime.of(2026, 4, 1, 8, 0, 0)
        capturedRow.createdAt == null
    }

    def "畸形帧留档eventType与occurredAt为null原文照样全文落库"() {
        given: "半截帧——事件类型与平台时间均不可得"
        def brokenPayload = '{"time": 1717200000, "self_id": 10'

        when:
        def rawRef = archiver.archive("onebot-v11", "unknown", RawEventArchiver.DIRECTION_IN,
                null, null, null, brokenPayload)

        then:
        interaction { insertOnceWithGeneratedKey(1L) }
        rawRef.rawEventId() == "1"

        and:
        capturedRow.eventType == null
        capturedRow.occurredAt == null
        capturedRow.rawPayload == brokenPayload
    }

    def "出方向留档direction为out且dedupKey填平台messageId"() {
        when: "Sender 落平台 API 响应原文（发出过什么的审计落点）"
        def rawRef = archiver.archive("onebot-v11", "10000", RawEventArchiver.DIRECTION_OUT,
                "api.send_group_message", "7654321", null,
                '{"status":"ok","retcode":0,"data":{"message_id":7654321}}')

        then:
        interaction { insertOnceWithGeneratedKey(9900L) }
        rawRef.rawEventId() == "9900"

        and:
        capturedRow.direction == "out"
        capturedRow.eventType == "api.send_group_message"
        capturedRow.dedupKey == "7654321"
        capturedRow.occurredAt == null
    }
}
