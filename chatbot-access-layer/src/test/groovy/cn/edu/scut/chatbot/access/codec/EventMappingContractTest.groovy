package cn.edu.scut.chatbot.access.codec

import cn.edu.scut.chatbot.access.bootstrap.SnowflakeIdGenerator
import cn.edu.scut.chatbot.access.fixtures.SteadyClock
import cn.edu.scut.chatbot.access.mediastore.MediaTransferService
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.envelope.RawEventRef
import cn.edu.scut.chatbot.contract.in.BotEventPayload
import cn.edu.scut.chatbot.contract.in.GroupEventPayload
import cn.edu.scut.chatbot.contract.in.InteractionPayload
import cn.edu.scut.chatbot.contract.in.MemberEventPayload
import cn.edu.scut.chatbot.contract.message.MessagePart
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

import java.time.ZoneOffset

/**
 * 守护纪律：OneBot 事件 → 入原语映射总表逐行覆盖（SC-001 翻译保真 + data-model.md 第 4 节总表）。
 * 每一行映射都有测试在守：类型收敛正确、平台特有语义进 ext.qq.* 命名空间、
 * 归属判定（受影响者是 bot 自己还是普通成员）逐行验证。
 */
class EventMappingContractTest extends Specification {

    def mediaTransferService = Mock(MediaTransferService)
    def mapper = new ObjectMapper()
    def rawRef = new RawEventRef("onebot-v11", "9900", "json")

    EventTranslator translator

    def setup() {
        // 固定时钟下的真发号器：eventId 可预测；媒体转存 Mock 为原样返回（转存三分支另有专测）
        def clock = new SteadyClock(1775001600000L)
        def idGenerator = new SnowflakeIdGenerator(2, clock)
        mediaTransferService.transferInbound(_ as List<MessagePart>) >> { List<MessagePart> parts -> parts }
        translator = new EventTranslator(
                new GroupMessageCodec(new OneBotMessageParser()),
                new InteractionCodec(), new MemberEventCodec(),
                new BotEventCodec(), new GroupEventCodec(),
                new RawEventCodec(idGenerator),
                new DedupKeyResolver(), mediaTransferService, new EnvelopeValidator(),
                idGenerator, new cn.edu.scut.chatbot.access.bootstrap.TraceContextHelper(), clock)
    }

    private def translate(String eventJson) {
        def node = mapper.readTree(eventJson)
        return translator.translate(node, eventJson, rawRef, 1775001600412L)
    }

    // ===== notice.notify 系列 → Interaction =====

    def "群内戳一戳收敛Interaction的poke且poke类型进ext"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"notify","sub_type":"poke","group_id":123456789,"user_id":10001000,"target_id":10000,"poke_type":1}''')

        then:
        envelope.type() == PrimitiveTypeCodes.INTERACTION
        def payload = envelope.payload() as InteractionPayload
        payload.subType() == InteractionPayload.SUB_POKE
        payload.targetUserId() == "10000"
        payload.operatorUserId() == "10001000"
        envelope.extension()["ext.qq.poke_type"] == "1"
    }

    def "红包运与群荣誉分别收敛red_packet_lucky与honor"() {
        when:
        def lucky = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"notify","sub_type":"lucky_king","group_id":123456789,"user_id":10001000,"target_id":10086}''')

        then:
        (lucky.payload() as InteractionPayload).subType() == InteractionPayload.SUB_RED_PACKET_LUCKY

        when:
        def honor = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"notify","sub_type":"honor","group_id":123456789,"user_id":10001000,"honor_type":"talkative"}''')

        then:
        def honorPayload = honor.payload() as InteractionPayload
        honorPayload.subType() == InteractionPayload.SUB_HONOR
        honor.extension()["ext.qq.honor_type"] == "talkative"
    }

    def "群文件上传收敛file_upload"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_upload","group_id":123456789,"user_id":10001000,"file":{"id":"F1","name":"a.zip","size":1024}}''')

        then:
        (envelope.payload() as InteractionPayload).subType() == InteractionPayload.SUB_FILE_UPLOAD
    }

    def "群消息撤回与好友消息撤回都收敛message_recall且operator取user_id"() {
        when:
        def groupRecall = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_recall","group_id":123456789,"user_id":10086,"message_id":555}''')

        then:
        def payload = groupRecall.payload() as InteractionPayload
        payload.subType() == InteractionPayload.SUB_MESSAGE_RECALL
        payload.operatorUserId() == "10086"

        when:
        def friendRecall = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"friend_recall","user_id":10001000,"message_id":556}''')

        then:
        (friendRecall.payload() as InteractionPayload).subType() == InteractionPayload.SUB_MESSAGE_RECALL
    }

    // ===== request 系列 → Interaction =====

    def "好友请求收敛friend_request且flag进ext"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"request",
            "request_type":"friend","user_id":30003000,"comment":"我是滑稽","flag":"FLAG-1"}''')

        then:
        def payload = envelope.payload() as InteractionPayload
        payload.subType() == InteractionPayload.SUB_FRIEND_REQUEST
        payload.requestFlag() == "FLAG-1"
        envelope.extension()["ext.qq.request_flag"] == "FLAG-1"
        envelope.dedupKey() != null
    }

    def "加群请求收敛group_join_request且flag进ext"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"request",
            "request_type":"group","sub_type":"add","group_id":123456789,"user_id":30003000,"flag":"FLAG-2"}''')

        then:
        def payload = envelope.payload() as InteractionPayload
        payload.subType() == InteractionPayload.SUB_GROUP_JOIN_REQUEST
        envelope.extension()["ext.qq.request_flag"] == "FLAG-2"
    }

    // ===== 成员事件 → MemberEvent =====

    def "入群收敛member_join"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_increase","sub_type":"approve","group_id":123456789,"user_id":20002000,"operator_id":10000}''')

        then:
        (envelope.payload() as MemberEventPayload).subType() == MemberEventPayload.SUB_MEMBER_JOIN
    }

    def "主动退群收敛member_leave被踢收敛member_kicked"() {
        when:
        def leave = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_decrease","sub_type":"leave","group_id":123456789,"user_id":20002000,"operator_id":20002000}''')

        then:
        (leave.payload() as MemberEventPayload).subType() == MemberEventPayload.SUB_MEMBER_LEAVE

        when:
        def kicked = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_decrease","sub_type":"kick","group_id":123456789,"user_id":20002000,"operator_id":10086}''')

        then:
        def kickedPayload = kicked.payload() as MemberEventPayload
        kickedPayload.subType() == MemberEventPayload.SUB_MEMBER_KICKED
        kickedPayload.operatorUserId() == "10086"
    }

    def "管理员任免收敛admin_granted与admin_revoked"() {
        when:
        def granted = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_admin","sub_type":"set","group_id":123456789,"user_id":20002000}''')

        then:
        (granted.payload() as MemberEventPayload).subType() == MemberEventPayload.SUB_ADMIN_GRANTED

        when:
        def revoked = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_admin","sub_type":"unset","group_id":123456789,"user_id":20002000}''')

        then:
        (revoked.payload() as MemberEventPayload).subType() == MemberEventPayload.SUB_ADMIN_REVOKED
    }

    // ===== 禁言双分支：归属判定（受影响者是 bot 自己 → BotEvent，否则 MemberEvent） =====

    def "禁言他人收敛MemberEvent的muted且时长与原始子类型进字段"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_ban","sub_type":"ban","group_id":123456789,"user_id":20002000,"operator_id":10000,"duration":300}''')

        then:
        def payload = envelope.payload() as MemberEventPayload
        payload.subType() == MemberEventPayload.SUB_MUTED
        payload.durationSeconds() == 300L
        payload.operatorUserId() == "10000"
        envelope.userId() == "20002000"
        envelope.extension()["ext.qq.ban_sub_type"] == "ban"
    }

    def "解禁他人收敛unmuted"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_ban","sub_type":"lift_ban","group_id":123456789,"user_id":20002000,"operator_id":10000,"duration":0}''')

        then:
        (envelope.payload() as MemberEventPayload).subType() == MemberEventPayload.SUB_UNMUTED
    }

    def "bot被禁言收敛BotEvent的bot_muted"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_ban","sub_type":"ban","group_id":123456789,"user_id":10000,"operator_id":10086,"duration":600}''')

        then:
        def payload = envelope.payload() as BotEventPayload
        payload.subType() == BotEventPayload.SUB_BOT_MUTED
        payload.durationSeconds() == 600L
        payload.operatorUserId() == "10086"
    }

    def "bot被解禁收敛BotEvent的bot_unmuted"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_ban","sub_type":"lift_ban","group_id":123456789,"user_id":10000,"operator_id":10086,"duration":0}''')

        then:
        (envelope.payload() as BotEventPayload).subType() == BotEventPayload.SUB_BOT_UNMUTED
    }

    // ===== Bot 自身事件 =====

    def "bot被踢出群收敛kick_me为BotEvent的bot_kicked"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_decrease","sub_type":"kick_me","group_id":123456789,"user_id":10000,"operator_id":10086}''')

        then:
        def payload = envelope.payload() as BotEventPayload
        payload.subType() == BotEventPayload.SUB_BOT_KICKED
        payload.operatorUserId() == "10086"
        payload.groupId() == "123456789"
    }

    def "新好友收敛BotEvent的friend_added"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"friend_add","user_id":30003000}''')

        then:
        (envelope.payload() as BotEventPayload).subType() == BotEventPayload.SUB_FRIEND_ADDED
    }

    def "NapCat扩展bot_offline收敛BotEvent的bot_offline"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"bot_offline","user_id":10000,"tag":4,"message":"掉线"}''')

        then:
        (envelope.payload() as BotEventPayload).subType() == BotEventPayload.SUB_BOT_OFFLINE
    }

    // ===== NapCat 扩展 → 各原语 =====

    def "NapCat群名变更收敛GroupEvent的info_changed且新群名进ext"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_name","group_id":123456789,"user_id":10086,"group_name":"新群名"}''')

        then:
        (envelope.payload() as GroupEventPayload).subType() == GroupEventPayload.SUB_INFO_CHANGED
        envelope.extension()["ext.qq.group_name"] == "新群名"
    }

    def "NapCat表情回应收敛Interaction的reaction"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"msg_emoji_like","group_id":123456789,"user_id":10001000,"message_id":555,"emoji_id":76}''')

        then:
        (envelope.payload() as InteractionPayload).subType() == InteractionPayload.SUB_REACTION
    }

    def "NapCat群名片变更收敛MemberEvent的card_changed"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_card","group_id":123456789,"user_id":20002000,"card":"新名片"}''')

        then:
        (envelope.payload() as MemberEventPayload).subType() == MemberEventPayload.SUB_CARD_CHANGED
    }

    def "NapCat精华消息收敛Interaction的essence"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"group_essence","group_id":123456789,"user_id":10086,"message_id":555}''')

        then:
        (envelope.payload() as InteractionPayload).subType() == InteractionPayload.SUB_ESSENCE
    }

    // ===== 未建模兜底 → RawEvent =====

    def "NapCat未建模扩展收敛raw_event保真"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"gray_tip","group_id":123456789,"user_id":10001000,"content":"<灰条>"}''')

        then:
        envelope.type() == PrimitiveTypeCodes.RAW_EVENT
        (envelope.payload() as cn.edu.scut.chatbot.contract.in.RawEventPayload).reason() == "unknown_structure"
    }

    def "私聊消息第一版收敛raw_event保真"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"message",
            "message_type":"private","sub_type":"friend","message_id":777,"user_id":30003000,
            "message":[{"type":"text","data":{"text":"私聊"}}],"raw_message":"私聊",
            "sender":{"user_id":30003000,"nickname":"好友"}}''')

        then:
        envelope.type() == PrimitiveTypeCodes.RAW_EVENT
        envelope.dedupKey() != null
    }

    // ===== dedupKey 语义（T043）=====

    def "消息类事件取平台message_id作dedupKey"() {
        when:
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"message",
            "message_type":"group","sub_type":"normal","message_id":"98765","user_id":10001000,"group_id":123456789,
            "message":[{"type":"text","data":{"text":"hi"}}],"raw_message":"hi",
            "sender":{"user_id":10001000,"nickname":"n"}}''')

        then:
        envelope.dedupKey() == "98765"
    }

    def "notice类事件无原生ID退化为内容哈希且同报文稳定"() {
        given:
        def pokeJson = '''{"time":1717200000,"self_id":10000,"post_type":"notice",
            "notice_type":"notify","sub_type":"poke","group_id":123456789,"user_id":10001000,"target_id":10000}'''

        when:
        def first = translate(pokeJson)
        def second = translate(pokeJson)

        then:
        first.dedupKey() == second.dedupKey()
        first.dedupKey().length() <= 128
        and: "与消息类的 message_id 键空间天然不同（内容哈希恒 32 位十六进制）"
        first.dedupKey().matches("[0-9a-f]{32}")
    }

    // ===== 翻译内部异常兜底（T021 internal_error 分支的统一入口侧验证）=====

    def "群消息缺group_id触发信封校验异常收敛internal_error兜底保真"() {
        given: "畸形平台事件——群消息没有 group_id，信封非空校验在翻译内部抛出"
        def envelope = translate('''{"time":1717200000,"self_id":10000,"post_type":"message",
            "message_type":"group","sub_type":"normal","message_id":1110009,"user_id":10001000,
            "message":[{"type":"text","data":{"text":"hi"}}],"raw_message":"hi",
            "sender":{"user_id":10001000,"nickname":"n"}}''')

        expect: "翻译方法不抛异常，收敛 raw_event 兜底——原文已先留档，链路可回溯"
        envelope.type() == PrimitiveTypeCodes.RAW_EVENT
        (envelope.payload() as cn.edu.scut.chatbot.contract.in.RawEventPayload).reason() == "internal_error"
        envelope.rawRef() == rawRef
    }
}
