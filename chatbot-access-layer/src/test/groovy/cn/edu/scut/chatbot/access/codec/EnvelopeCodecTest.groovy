package cn.edu.scut.chatbot.access.codec

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope
import cn.edu.scut.chatbot.contract.envelope.PlatformCodes
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.envelope.RawEventRef
import cn.edu.scut.chatbot.contract.error.ErrorCodes
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload
import cn.edu.scut.chatbot.contract.in.InteractionPayload
import cn.edu.scut.chatbot.contract.in.SenderProfile
import cn.edu.scut.chatbot.contract.message.AtPart
import cn.edu.scut.chatbot.contract.message.ImagePart
import cn.edu.scut.chatbot.contract.message.MediaTransferStatusCodes
import cn.edu.scut.chatbot.contract.message.TextPart
import cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload
import spock.lang.Specification

/**
 * 守护纪律：事件信封的平铺多态编解码（契约 contracts/event-envelope.md 第 1 节）。
 * 信封按顶层 type 查映射表解释 payload、消息段按 part 标记解释——载荷永不出现 Java 类名；
 * 往返一致保证接入层与 agent-core 两侧对同一份线格式认知相同。
 */
class EnvelopeCodecTest extends Specification {

    def codec = new EnvelopeCodec()

    def "群消息信封序列化后反序列化往返字段全等"() {
        given: "含 @ 与图片段、extension、rawRef 的完整入信封"
        def envelope = new EventEnvelope(
                "3849281194743615489", null, "1234567",
                PlatformCodes.ONEBOT_V11, "10000", "123456789", "10001000",
                PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload(
                        "1234567", "normal",
                        new SenderProfile("10001000", "滑稽", "", "member", "", ""),
                        [
                                new AtPart(AtPart.TYPE_AT, "10000"),
                                new TextPart(TextPart.TYPE_TEXT, " 看看这张图"),
                                new ImagePart(ImagePart.TYPE_IMAGE,
                                        "https://multimedia.nt.qq.com.cn/x.jpg",
                                        "/data/media/img/ab/cd/abcd1234.jpg",
                                        MediaTransferStatusCodes.TRANSFERRED, "6B4DE3DF.jpg", 204800L)
                        ],
                        "[CQ:at,qq=10000] 看看这张图"),
                1717200000000L, 1717200000412L,
                ["ext.qq.self_sent": "false"],
                new RawEventRef(PlatformCodes.ONEBOT_V11, "8821", RawEventRef.FORMAT_JSON),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

        when:
        def json = codec.serialize(envelope)
        def restored = codec.deserialize(json)

        then: "全部字段往返全等，payload 还原为 GroupMessagePayload 且段类型正确"
        restored == envelope
        restored.payload() instanceof GroupMessagePayload
        def payload = restored.payload() as GroupMessagePayload
        payload.parts()[0] instanceof AtPart
        payload.parts()[1] instanceof TextPart
        payload.parts()[2] instanceof ImagePart
        (payload.parts()[2] as ImagePart).fileSize() == 204800L
        payload.sender() == envelope.payload().sender()
    }

    def "按信封顶层type多态还原出正确的payload类型"() {
        given: "一条投递回执信封"
        def report = new EventEnvelope(
                "1", null, "1",
                PlatformCodes.ONEBOT_V11, "10000", null, null,
                PrimitiveTypeCodes.DELIVERY_REPORT,
                new DeliveryReportPayload("3849281194743617536", "7654321",
                        "success", null, null, null, 1717200006801L, false),
                null, 1717200006801L, [:], null, null)

        and: "一条戳一戳互动信封"
        def poke = new EventEnvelope(
                "2", null, "hash1",
                PlatformCodes.ONEBOT_V11, "10000", "123456789", "10001000",
                PrimitiveTypeCodes.INTERACTION,
                new InteractionPayload("poke", "10000", "10001000", null),
                1717200000000L, 1717200000001L, [:], null, null)

        when:
        def restoredReport = codec.deserialize(codec.serialize(report))
        def restoredPoke = codec.deserialize(codec.serialize(poke))

        then:
        restoredReport.payload() instanceof DeliveryReportPayload
        (restoredReport.payload() as DeliveryReportPayload).outboxId() == "3849281194743617536"
        restoredPoke.payload() instanceof InteractionPayload
        (restoredPoke.payload() as InteractionPayload).subType() == "poke"
    }

    def "出方向消息段按part标记多态还原"() {
        given: "含文本段与本地媒体段的发群消息信封"
        def outbound = new EventEnvelope(
                null, "3849281194743617900", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SEND_GROUP_MESSAGE,
                new SendGroupMessagePayload(
                        [
                                new TextPart(TextPart.TYPE_TEXT, "刚生成的图："),
                                new ImagePart(ImagePart.TYPE_IMAGE, null,
                                        "/data/media/out/gen.png",
                                        MediaTransferStatusCodes.NONE, "gen.png", null)
                        ], null),
                null, null, [:], null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

        when:
        def restored = codec.deserialize(codec.serialize(outbound))

        then:
        restored == outbound
        def parts = (restored.payload() as SendGroupMessagePayload).parts()
        parts[0] instanceof TextPart
                parts[1] instanceof ImagePart
        (parts[1] as ImagePart).storedRef() == "/data/media/out/gen.png"
    }

    def "序列化结果不携带Java类名与class类型信息"() {
        given:
        def envelope = new EventEnvelope(
                "1", null, "1", PlatformCodes.ONEBOT_V11, "10000", "1", "1",
                PrimitiveTypeCodes.INTERACTION,
                new InteractionPayload("poke", null, null, null),
                1L, 2L, [:], null, null)

        when:
        def json = codec.serialize(envelope)

        then:
        !json.contains("@class")
        !json.contains("cn.edu.scut")
        !json.contains("InteractionPayload")
    }

    def "非法JSON的信封拒绝还原"() {
        when:
        codec.deserialize("{ 半截帧")

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE
    }

    def "未知type的信封按毒消息拒绝"() {
        when:
        codec.deserialize('{"type":"future_primitive","payload":{}}')

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE
    }

    def "缺少type字段的信封拒绝还原"() {
        when:
        codec.deserialize('{"eventId":"1"}')

        then:
        thrown(NonRetryableException)
    }

    def "未知消息段part标记拒绝还原"() {
        given:
        def json = '''{
            "eventId":"1","outboxId":null,"dedupKey":"1","platform":"onebot-v11",
            "botId":"10000","groupId":"1","userId":"1","type":"send_group_message",
            "payload":{"parts":[{"part":"hologram","text":"?"}],"replyToMessageId":null},
            "occurredAt":null,"receivedAt":null,"extension":{},"rawRef":null,"traceContext":"00-1-2-01"}'''

        when:
        codec.deserialize(json)

        then:
        thrown(NonRetryableException)
    }

    def "撤回与表情回应及互动回复载荷按默认分支往返全等"() {
        given: "三种无消息段列表的出原语（映射表默认分支的 treeToValue 还原路径）"
        def recall = new EventEnvelope(
                null, "3849281194743617901", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.RECALL_MESSAGE,
                new cn.edu.scut.chatbot.contract.out.RecallMessagePayload("7654321"),
                null, null, [:], null, "00-1-2-01")
        def reaction = new EventEnvelope(
                null, "3849281194743617902", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SET_REACTION,
                new cn.edu.scut.chatbot.contract.out.SetReactionPayload("7654321", "76", true),
                null, null, [:], null, "00-1-2-01")
        def interactionReply = new EventEnvelope(
                null, "3849281194743617903", null,
                PlatformCodes.ONEBOT_V11, "10000", "123456789", null,
                PrimitiveTypeCodes.SEND_INTERACTION_REPLY,
                new cn.edu.scut.chatbot.contract.out.SendInteractionReplyPayload("FLAG-9", false, "不想加"),
                null, null, [:], null, "00-1-2-01")

        when:
        def restoredRecall = codec.deserialize(codec.serialize(recall))
        def restoredReaction = codec.deserialize(codec.serialize(reaction))
        def restoredReply = codec.deserialize(codec.serialize(interactionReply))

        then:
        restoredRecall == recall
        restoredRecall.payload() instanceof cn.edu.scut.chatbot.contract.out.RecallMessagePayload
                restoredReaction == reaction
        (restoredReaction.payload() as cn.edu.scut.chatbot.contract.out.SetReactionPayload).isCancel()
        restoredReply == interactionReply
        (restoredReply.payload() as cn.edu.scut.chatbot.contract.out.SendInteractionReplyPayload).reason() == "不想加"
    }

    def "payload为空的信封拒绝还原"() {
        given: "type 合法但 payload 缺失——按毒消息处理"
        def json = '{"eventId":"1","type":"raw_event","payload":null}'

        when:
        codec.deserialize(json)

        then:
        thrown(NonRetryableException)
    }
}
