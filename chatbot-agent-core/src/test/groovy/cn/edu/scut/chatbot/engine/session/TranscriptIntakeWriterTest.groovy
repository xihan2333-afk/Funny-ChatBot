// 守护纪律：存了再筛——入方向三类事实全部落转录（设计方案 2.2）；
// 群友消息源头截断 2000 字符宽松上限；事件条目带一行描述（可读即素材）
package cn.edu.scut.chatbot.engine.session

import cn.edu.scut.chatbot.bootstrap.IdGenerator
import cn.edu.scut.chatbot.context.link.LinkPrefetcher
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes
import cn.edu.scut.chatbot.contract.in.*
import cn.edu.scut.chatbot.contract.message.ImagePart
import cn.edu.scut.chatbot.contract.message.TextPart
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.SeededIdGenerator
import cn.edu.scut.chatbot.loop.message.DeliveryReportEntry
import cn.edu.scut.chatbot.loop.message.EventEntry
import cn.edu.scut.chatbot.loop.message.UserMessage
import spock.lang.Specification

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class TranscriptIntakeWriterTest extends Specification {

    def sessionManager = Mock(SessionManager)
    def prefetchCalls = new CopyOnWriteArrayList()
    LinkPrefetcher linkPrefetcher = Mock(LinkPrefetcher) {
        prefetchAndAppend(*_) >> { AgentSessionDO s, String text, String sharer ->
            prefetchCalls << [s, text, sharer]
        }
    }
    FixedClock clock = new FixedClock()
    IdGenerator idGenerator = new SeededIdGenerator()

    TranscriptIntakeWriter writer = new TranscriptIntakeWriter(sessionManager, idGenerator, clock, linkPrefetcher)

    AgentSessionDO session = sessionOf(777L)

    /** 转录写入捕获器（writeInbound 一次写一批一条；闭包参数与方法实参个数一致才走展开调用） */
    private List captureEntries(int times) {
        def entries = new CopyOnWriteArrayList()
        times * sessionManager.appendTranscript(_ as AgentSessionDO, _, null) >> { session, batch, runId ->
            entries.addAll(batch)
        }
        return entries
    }

    def setup() {
        sessionManager.getOrCreateSession(*_) >> session
    }

    def "群消息写为UserMessage 超长文本段源头截断2000字符 非文本段原样保留"() {
        given: "一条 2500 字符文本 + 一张图片的群消息"
        def longText = "啊" * 2500
        def image = new ImagePart("image", "https://cdn.example.com/img.jpg", null, null, "f.jpg", null)
        def envelope = envelope(PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload("msg-1", "normal", sender("10086"),
                        List.of(new TextPart("text", longText), image), longText))
        def entries = captureEntries(1)

        when:
        writer.writeInbound(envelope)

        then: "入库的是 UserMessage，文本段已宽松截断（保头部 + 尾部注明原文长度）"
        entries.size() == 1
        def userMessage = entries[0] as UserMessage
        userMessage.messageId() == "msg-1"
        userMessage.senderUserId() == "10086"
        def truncatedText = (userMessage.parts()[0] as TextPart).text()
        truncatedText.length() < longText.length()
        truncatedText.startsWith("啊啊")
        truncatedText.contains("2500")
        and: "图片段原样保留（截断只针对文本段）"
        userMessage.parts()[1].is(image)
        and: "时间取信封事件时间、引用取原始报文 ID"
        userMessage.occurredAt() == Instant.ofEpochMilli(envelope.occurredAt())
        userMessage.sourceRef() == "raw-9"
    }

    def "成员事件写为EventEntry 含属性与一行描述 禁言带时长"() {
        given: "管理员 10010 把用户 10086 禁言 600 秒"
        def envelope = envelope(PrimitiveTypeCodes.MEMBER_EVENT,
                new MemberEventPayload(MemberEventPayload.SUB_MUTED, "10010", 600L))
        def entries = captureEntries(1)

        when:
        writer.writeInbound(envelope)

        then:
        entries.size() == 1
        def event = entries[0] as EventEntry
        event.eventCategory() == EventEntry.CATEGORY_MEMBER_CHANGE
        event.weight() == EventEntry.WEIGHT_MEMBER_CHANGE
        event.attributes()["subType"] == "muted"
        event.attributes()["operatorUserId"] == "10010"
        event.attributes()["durationSeconds"] == "600"
        and: "一行描述：谁、发生了什么、被谁操作（投影默认形态的素材）"
        event.summaryLine().contains("10086")
        event.summaryLine().contains("被禁言")
        event.summaryLine().contains("600 秒")
        event.summaryLine().contains("10010")
    }

    def "被移出群的一行描述携带操作者"() {
        given:
        def envelope = envelope(PrimitiveTypeCodes.MEMBER_EVENT,
                new MemberEventPayload(MemberEventPayload.SUB_MEMBER_KICKED, "10010", null))
        def entries = captureEntries(1)

        when:
        writer.writeInbound(envelope)

        then:
        def event = entries[0] as EventEntry
        event.summaryLine().contains("被移出了群")
        event.summaryLine().contains("10010")
    }

    def "互动事件与群事件写为EventEntry 一行描述可读"() {
        given: "用户 10086 戳了戳 10000；以及一条群名变更事件"
        def poke = envelope(PrimitiveTypeCodes.INTERACTION,
                new InteractionPayload(InteractionPayload.SUB_POKE, "10000", null, null))
        def groupChange = envelope(PrimitiveTypeCodes.GROUP_EVENT,
                new GroupEventPayload(GroupEventPayload.SUB_INFO_CHANGED))
        def entries = captureEntries(2)

        when:
        writer.writeInbound(poke)
        writer.writeInbound(groupChange)

        then:
        def interaction = entries[0] as EventEntry
        interaction.eventCategory() == EventEntry.CATEGORY_INTERACTION
        interaction.attributes()["targetUserId"] == "10000"
        interaction.summaryLine().contains("戳了戳")
        interaction.summaryLine().contains("10000")

        and:
        def groupEvent = entries[1] as EventEntry
        groupEvent.eventCategory() == EventEntry.CATEGORY_GROUP_CHANGE
        groupEvent.summaryLine().contains("群信息变更")
    }

    def "投递回执写为DeliveryReportEntry 失败携带原因 时间取平台尝试时刻"() {
        given:
        def report = new DeliveryReportPayload("123456", null, "failed", "forbidden",
                "机器人已被移出群", null, 1775001600123L, false)
        def entries = captureEntries(1)

        when:
        writer.writeDeliveryReport(report, "qq", "10000", "80001")

        then:
        entries.size() == 1
        def entry = entries[0] as DeliveryReportEntry
        entry.outboxId() == "123456"
        entry.status() == "failed"
        entry.failureKind() == "forbidden"
        entry.failureDetail() == "机器人已被移出群"
        entry.occurredAt() == Instant.ofEpochMilli(1775001600123L)
    }

    def "回执群标识缺失不写（无会话可挂）"() {
        given:
        def report = new DeliveryReportPayload("123456", null, "success", null, null, null, null, false)

        when: "三元组不完整的回执"
        writer.writeDeliveryReport(report, null, "10000", "80001")
        writer.writeDeliveryReport(report, "qq", null, "80001")
        writer.writeDeliveryReport(report, "qq", "10000", null)

        then: "都不产生转录写入"
        0 * sessionManager.getOrCreateSession(_, _, _)
        0 * sessionManager.appendTranscript(_, _, _)
    }

    def "回执attemptedAt缺失以服务端时钟兜底打时间"() {
        given:
        def report = new DeliveryReportPayload("123456", null, "success", null, null, null, null, false)
        def entries = captureEntries(1)

        when:
        writer.writeDeliveryReport(report, "qq", "10000", "80001")

        then:
        (entries[0] as DeliveryReportEntry).occurredAt() == clock.instant()
    }

    def "BotEvent非销毁分支写转录 全局事件无群会话不写"() {
        given: "Bot 被禁言 1800 秒（群内事件）"
        def muted = envelope(PrimitiveTypeCodes.BOT_EVENT,
                new BotEventPayload(BotEventPayload.SUB_BOT_MUTED, "10010", 1800L, "80001"))
        def entries = captureEntries(1)

        when:
        writer.writeBotEvent(muted, muted.payload() as BotEventPayload)

        then:
        def event = entries[0] as EventEntry
        event.eventCategory() == EventEntry.CATEGORY_BOT_EVENT
        event.attributes()["durationSeconds"] == "1800"
        event.summaryLine().contains("你被禁言了")
        event.summaryLine().contains("1800 秒")

        when: "全局事件（friend_added 等）groupId 为 null"
        def global = new EventEnvelope("evt-global", null, "global-1", "qq", "10000",
                null, "10086", PrimitiveTypeCodes.BOT_EVENT,
                new BotEventPayload(BotEventPayload.SUB_FRIEND_ADDED, null, null, null),
                clock.millis(), clock.millis(), Map.of(), null, null)
        writer.writeBotEvent(global, global.payload() as BotEventPayload)

        then: "不写转录（无群会话可挂）"
        0 * sessionManager.appendTranscript(_, _, _)
    }

    def "messageId缺失的信封以发号器兜底占位"() {
        given: "群消息载荷 messageId 为 null（异常形态）"
        def envelope = envelope(PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload(null, "normal", sender("10086"),
                        List.of(new TextPart("text", "你好")), "你好"))
        def entries = captureEntries(1)

        when:
        writer.writeInbound(envelope)

        then: "条目仍获得一个确定的新 ID（不因缺 ID 丢事实）"
        entries.size() == 1
        (entries[0] as UserMessage).messageId() == Long.toUnsignedString(idGenerator.nextId() - 1)
    }

    def "raw_event等未支持载荷不产生转录条目（留档在接入层）"() {
        given:
        def envelope = envelope(PrimitiveTypeCodes.RAW_EVENT,
                new RawEventPayload("unknown", new cn.edu.scut.chatbot.contract.envelope.RawEventRef("qq", "raw-9", "json")))

        when:
        writer.writeInbound(envelope)

        then:
        0 * sessionManager.getOrCreateSession(_, _, _)
        0 * sessionManager.appendTranscript(_, _, _)
    }

    def "Bot自身发言的回显跳过不写转录（否则同句发言双份进历史）"() {
        given: "接入层把 message_sent 翻成的群消息回流——sender 就是 bot 自己"
        def echo = envelope(PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload("msg-echo", "normal", sender("10000"),
                        List.of(new TextPart("text", "我刚说过的那句话")), null))

        when:
        writer.writeInbound(echo)

        then: "不建会话不写转录（发言已由 AssistantMessage 侧承载）"
        0 * sessionManager.getOrCreateSession(_, _, _)
        0 * sessionManager.appendTranscript(_, _, _)
    }

    def "含http的群消息异步触发链接预取 文本与分享人随之传递"() {
        given: "消息文本带 URL"
        def envelope = envelope(PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload("msg-2", "normal", sender("10086"),
                        List.of(new TextPart("text", "看看这个 https://example.com/page?x=1 很有趣")), null))

        when:
        writer.writeInbound(envelope)

        then: "预取在虚拟线程异步发生（卡片是增强信息，不挡登记）"
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until({ prefetchCalls.size() == 1 })
        prefetchCalls[0][0].is(session)
        prefetchCalls[0][1] == "看看这个 https://example.com/page?x=1 很有趣"
        prefetchCalls[0][2] == "10086"
    }

    def "不含http的群消息不触发预取"() {
        given:
        def envelope = envelope(PrimitiveTypeCodes.GROUP_MESSAGE,
                new GroupMessagePayload("msg-3", "normal", sender("10086"),
                        List.of(new TextPart("text", "普通聊天内容")), null))

        when:
        writer.writeInbound(envelope)

        then: "登记完成后一小段时间内仍无预取（负向确定性观察）"
        org.awaitility.Awaitility.await().during(java.time.Duration.ofMillis(300))
                .atMost(java.time.Duration.ofSeconds(5))
                .until({ prefetchCalls.isEmpty() })
    }

    // ===== 构造辅助 =====

    private static SenderProfile sender(String userId) {
        return new SenderProfile(userId, "测试用户" + userId, "", "member", "1", "")
    }

    private AgentSessionDO sessionOf(long id) {
        AgentSessionDO session = new AgentSessionDO()
        session.setId(id)
        session.setPlatform("qq")
        session.setBotId("10000")
        session.setGroupId("80001")
        return session
    }

    private EventEnvelope envelope(String type, def payload) {
        return new EventEnvelope("evt-" + System.nanoTime(), null, "dedup-" + System.nanoTime(),
                "qq", "10000", "80001", "10086", type, payload,
                1775001600000L, 1775001600000L, Map.of(),
                new cn.edu.scut.chatbot.contract.envelope.RawEventRef("qq", "raw-9", "json"), null)
    }
}
