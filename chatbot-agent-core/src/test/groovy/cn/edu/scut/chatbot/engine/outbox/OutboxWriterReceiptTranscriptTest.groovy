// 守护纪律：回执事实进历史（下一次推理才可见——不注入进行中的生成）；
// 幂等口径——仅首次结算（原状态 PENDING）写转录，重投回执不双写；
// 手工补录是回执事实的等价登记（合成回执载荷同写转录）
package cn.edu.scut.chatbot.engine.outbox

import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper
import cn.edu.scut.chatbot.engine.session.TranscriptIntakeWriter
import cn.edu.scut.chatbot.engine.turn.TurnMapper
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.SeededIdGenerator
import spock.lang.Specification

class OutboxWriterReceiptTranscriptTest extends Specification {

    def outboxMapper = Mock(OutboxMapper)
    def effectsQueueMapper = Mock(EffectsQueueMapper)
    def turnMapper = Mock(TurnMapper)
    def triggerEventMapper = Mock(TriggerEventMapper)
    def transcriptIntakeWriter = Mock(TranscriptIntakeWriter)

    OutboxWriter writer = new OutboxWriter(outboxMapper, effectsQueueMapper, turnMapper,
            new SeededIdGenerator(), new FixedClock(), transcriptIntakeWriter, triggerEventMapper)

    def "回执结算成功后写回执转录条目（群标识取自outbox行）"() {
        given: "一条在途（PENDING）的出站消息——turn 关联为空，隔离 Turn 推进"
        def outbox = outboxOf(123L, null, OutboxDO.STATUS_PENDING)
        outboxMapper.selectById(123L) >> outbox
        def report = new DeliveryReportPayload("123", "pm-9", "success", null, null, null,
                1775001600123L, false)

        when:
        writer.settleDelivery(report)

        then:
        1 * outboxMapper.settleReport(123L, OutboxDO.STATUS_SETTLED, _, null, _)
        1 * transcriptIntakeWriter.writeDeliveryReport(report, "qq", "10000", "80001")
        and: "非 Turn 关联不推进结算"
        0 * turnMapper.selectById(_)
    }

    def "失败回执按failed结算并携带失败分类"() {
        given:
        def outbox = outboxOf(456L, null, OutboxDO.STATUS_PENDING)
        outboxMapper.selectById(456L) >> outbox
        def report = new DeliveryReportPayload("456", null, "failed", "group_muted",
                "机器人被禁言", null, 1775001600456L, false)

        when:
        writer.settleDelivery(report)

        then:
        1 * outboxMapper.settleReport(456L, OutboxDO.STATUS_FAILED, _, "group_muted", _)
        1 * transcriptIntakeWriter.writeDeliveryReport(report, "qq", "10000", "80001")
    }

    def "重投回执（行已结算）不双写转录——至少一次语义下幂等"() {
        given: "outbox 行已经是 SETTLED（首条回执已处理过）"
        def outbox = outboxOf(123L, null, OutboxDO.STATUS_SETTLED)
        outboxMapper.selectById(123L) >> outbox
        def duplicateReport = new DeliveryReportPayload("123", "pm-10", "success", null, null, null,
                1775001600999L, false)

        when:
        writer.settleDelivery(duplicateReport)

        then: "行照常回填（后到的回执信息不丢），但转录不双写"
        1 * outboxMapper.settleReport(123L, OutboxDO.STATUS_SETTLED, _, null, _)
        0 * transcriptIntakeWriter.writeDeliveryReport(_, _, _, _)
    }

    def "回执关联不存在的outbox行不写转录（孤儿回执告警留痕不抛）"() {
        given:
        outboxMapper.selectById(404L) >> null
        def report = new DeliveryReportPayload("404", null, "success", null, null, null,
                1775001600000L, false)

        when:
        writer.settleDelivery(report)

        then:
        notThrown(Exception)
        0 * transcriptIntakeWriter.writeDeliveryReport(_, _, _, _)
    }

    def "手工补录构造合成回执同写转录（补录是回执事实的等价登记）"() {
        given:
        def outbox = outboxOf(789L, null, OutboxDO.STATUS_PENDING)
        outboxMapper.selectById(789L) >> outbox

        when: "管理面按字段补录一次失败结算"
        writer.settleDeliveryStatus(789L, OutboxDO.STATUS_FAILED, null, "forbidden")

        then:
        1 * outboxMapper.settleReport(789L, OutboxDO.STATUS_FAILED, _, "forbidden", _)
        and: "合成回执载荷：失败分类携带、群标识取自 outbox 行"
        1 * transcriptIntakeWriter.writeDeliveryReport({ DeliveryReportPayload synthesized ->
            synthesized.outboxId() == "789" && synthesized.status() == "failed"
                    && synthesized.failureKind() == "forbidden"
        }, "qq", "10000", "80001")

        when: "补录成功结算则合成 success 形态"
        writer.settleDeliveryStatus(789L, OutboxDO.STATUS_SETTLED, null, null)

        then:
        1 * outboxMapper.settleReport(789L, OutboxDO.STATUS_SETTLED, _, null, _)
        1 * transcriptIntakeWriter.writeDeliveryReport({ DeliveryReportPayload synthesized ->
            synthesized.status() == "success"
        }, "qq", "10000", "80001")
    }

    def "重复补录（行已非PENDING）不再写转录"() {
        given: "行已被补录过一次（FAILED）"
        def outbox = outboxOf(789L, null, OutboxDO.STATUS_FAILED)
        outboxMapper.selectById(789L) >> outbox

        when:
        writer.settleDeliveryStatus(789L, OutboxDO.STATUS_FAILED, null, "forbidden")

        then:
        1 * outboxMapper.settleReport(789L, OutboxDO.STATUS_FAILED, _, "forbidden", _)
        0 * transcriptIntakeWriter.writeDeliveryReport(_, _, _, _)
    }

    def "补录目标不存在不写转录"() {
        given:
        outboxMapper.selectById(404L) >> null

        when:
        writer.settleDeliveryStatus(404L, OutboxDO.STATUS_SETTLED, null, null)

        then:
        0 * transcriptIntakeWriter.writeDeliveryReport(_, _, _, _)
    }

    private OutboxDO outboxOf(long id, Long turnId, String status) {
        OutboxDO outbox = new OutboxDO()
        outbox.setId(id)
        outbox.setPlatform("qq")
        outbox.setBotId("10000")
        outbox.setGroupId("80001")
        outbox.setType("send_group_message")
        outbox.setTurnId(turnId)
        outbox.setStatus(status)
        return outbox
    }
}
