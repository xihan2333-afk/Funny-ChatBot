package cn.edu.scut.chatbot.access.codec

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

/**
 * 守护纪律：归属判定表独立可测（data-model.md 第 4 节 + T046「判定表独立可测」要求）。
 * OneBot 事件以「受影响者是谁」归属：self_id 出现在 user_id → BotEvent，否则 MemberEvent。
 * 判定表被 BotEventCodec 与 MemberEventCodec 共用——字段缺失时宁可判「他人」不误判「自己」。
 */
class EventOwnershipTest extends Specification {

    def mapper = new ObjectMapper()

    private def event(String json) {
        mapper.readTree(json)
    }

    def "groupBan的user_id等于self_id时受影响者是bot自己"() {
        expect:
        EventOwnership.banAffectsBot(event('{"self_id":10000,"user_id":10000,"sub_type":"ban"}'))
    }

    def "groupBan的user_id不等于self_id时受影响者是普通成员"() {
        expect:
        !EventOwnership.banAffectsBot(event('{"self_id":10000,"user_id":20002000,"sub_type":"ban"}'))
    }

    def "groupBan缺self_id字段时不误判为自己"() {
        expect: "self_id 不可得 → 无法确认受影响者是 bot，宁可判「他人」走 MemberEvent"
        !EventOwnership.banAffectsBot(event('{"user_id":20002000,"sub_type":"ban"}'))
    }

    def "groupBan缺user_id字段时同样不误判"() {
        expect:
        !EventOwnership.banAffectsBot(event('{"self_id":10000,"sub_type":"ban"}'))
    }

    def "groupDecrease的kick_me表示bot自己被踢"() {
        expect:
        EventOwnership.decreaseAffectsBot(event('{"self_id":10000,"sub_type":"kick_me","user_id":10000}'))
    }

    def "groupDecrease的leave与kick是普通成员事件"() {
        expect:
        !EventOwnership.decreaseAffectsBot(event('{"self_id":10000,"sub_type":"leave","user_id":20002000}'))
        !EventOwnership.decreaseAffectsBot(event('{"self_id":10000,"sub_type":"kick","user_id":20002000}'))
    }

    def "groupDecrease缺sub_type时不判定为bot自己"() {
        expect:
        !EventOwnership.decreaseAffectsBot(event('{"self_id":10000,"user_id":20002000}'))
    }
}
