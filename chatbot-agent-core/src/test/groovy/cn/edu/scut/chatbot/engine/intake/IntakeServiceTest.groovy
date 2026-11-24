// 守护纪律：登记成功才写转录（转录与触发器同生）；重复投递幂等拦截后不重复写
// ——「存了再筛」不因至少一次投递语义变成「存了又存」
package cn.edu.scut.chatbot.engine.intake

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload
import cn.edu.scut.chatbot.contract.in.SenderProfile
import cn.edu.scut.chatbot.contract.message.TextPart
import cn.edu.scut.chatbot.engine.session.TranscriptIntakeWriter
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.SeededIdGenerator
import org.springframework.dao.DuplicateKeyException
import spock.lang.Specification

class IntakeServiceTest extends Specification {

    def triggerEventMapper = Mock(TriggerEventMapper)
    def groupMapper = Mock(GroupMapper)
    def groupConfigMapper = Mock(GroupConfigMapper)
    def transcriptIntakeWriter = Mock(TranscriptIntakeWriter)

    IntakeService service = new IntakeService(triggerEventMapper, groupMapper, groupConfigMapper,
            new SeededIdGenerator(), new FixedClock(),
            { envelope -> } as IntakeService.DeliverySettlementSink,
            { envelope, payload -> } as IntakeService.LifecycleHook,
            transcriptIntakeWriter)

    /** 服务中群形态（upsert 双写打桩 + in_service=1 的群行） */
    private void groupInService() {
        groupMapper.upsertDiscovered(*_) >> 1
        groupMapper.selectByGroup(*_) >> inServiceGroup()
        groupConfigMapper.upsertDefault(*_) >> 1
    }

    private GroupDO inServiceGroup() {
        def group = new GroupDO()
        group.setInService(1)
        group.setLifecycle(GroupDO.LIFECYCLE_ACTIVE)
        return group
    }

    def "登记成功后同事务写转录（触发器与转录条目同生）"() {
        given:
        groupInService()
        def envelope = groupMessage("msg-1")
        triggerEventMapper.insertTrigger(_) >> 1

        when:
        service.registerTrigger(envelope)

        then:
        1 * transcriptIntakeWriter.writeInbound(envelope)
    }

    def "重复投递被幂等拦截后不写转录（uk_dedup 拒插）"() {
        given:
        groupInService()
        def envelope = groupMessage("msg-1")
        triggerEventMapper.insertTrigger(_) >> { throw new DuplicateKeyException("uk_dedup") }

        when:
        service.registerTrigger(envelope)

        then: "静默返回——不抛异常也不重复写转录"
        notThrown(Exception)
        0 * transcriptIntakeWriter.writeInbound(_)
    }

    def "未服务群不登记触发器也不写转录（运营决策不是系统判断）"() {
        given: "群在服务名单外（in_service=0）"
        groupMapper.upsertDiscovered(*_) >> 1
        groupMapper.selectByGroup(*_) >> inServiceGroup().tap { it.setInService(0) }
        groupConfigMapper.upsertDefault(*_) >> 1

        when:
        service.registerTrigger(groupMessage("msg-2"))

        then:
        0 * triggerEventMapper.insertTrigger(_)
        0 * transcriptIntakeWriter.writeInbound(_)
    }

    private static EventEnvelope groupMessage(String messageId) {
        return new EventEnvelope("evt-" + messageId, null, messageId,
                "qq", "10000", "80001", "10086", PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload(messageId, "normal",
                        new SenderProfile("10086", "测试用户", "", "member", "1", ""),
                        List.of(new TextPart("text", "你好")), null),
                1775001600000L, 1775001600000L, Map.of(), null, null)
    }
}
