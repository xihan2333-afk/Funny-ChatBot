// 守护纪律：压缩切点永不落工具对中间（spec US7 验收场景 2）；五段摘要结构；增量合并；
// 压缩完成降档 quiet + 发"建议沉淀"事件（发不反向耦合消费方进度）
package cn.edu.scut.chatbot.context.compaction

import cn.edu.scut.chatbot.engine.session.AgentSessionDO
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper
import cn.edu.scut.chatbot.engine.session.SessionManager
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.SeededIdGenerator
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import cn.edu.scut.chatbot.loop.message.SummaryEntry
import cn.edu.scut.chatbot.loop.message.ToolResultMessage
import cn.edu.scut.chatbot.loop.message.UserMessage
import cn.edu.scut.chatbot.memory.sediment.SedimentJobWriter
import spock.lang.Specification

import java.time.Instant

class CompactionTest extends Specification {

    private SedimentJobWriter noopJobWriter() {
        return { group, summaryEntryId, range -> } as SedimentJobWriter
    }

    private cn.edu.scut.chatbot.memory.social.GroupStateService noopGroupState() {
        return [
                onGroupMessage       : { group, sender, text -> },
                onBotSend            : { group -> },
                onCompactionRefreshed: { group, topicDigest, vibe -> },
                read                 : { group -> null },
                rebuild              : { group -> },
                renderDigest         : { snapshot -> "" }
        ] as cn.edu.scut.chatbot.memory.social.GroupStateService
    }

    private cn.edu.scut.chatbot.memory.social.TopicLifecycleService noopTopicLifecycle() {
        return [
                onCompactionRefreshed: { group, topics -> },
                onGroupMessage       : { group, text -> },
                runDailyCloseSweep   : { -> }
        ] as cn.edu.scut.chatbot.memory.social.TopicLifecycleService
    }

    def compactor = new TranscriptCompactor(null, null, null, null, { event -> },
            new DeterministicSummaryGenerator(), noopJobWriter(), noopGroupState(), noopTopicLifecycle())

    private UserMessage user(String id, int offset) {
        return new UserMessage(id, Instant.ofEpochSecond(1775000000 + offset), 50, null, "10086",
                List.of(new cn.edu.scut.chatbot.contract.message.TextPart("text", "消息" + id)))
    }

    private AssistantMessage toolCallMessage(String id, String callId, int offset) {
        return new AssistantMessage(id, Instant.ofEpochSecond(1775000000 + offset), 60, null,
                "", List.of(new AssistantMessage.ToolCallSegment(callId, "fetchWeb", "{}")),
                AssistantMessage.STOP_REASON_TOOL_USE, AssistantMessage.TokenUsage.ZERO)
    }

    private ToolResultMessage toolResult(String id, String callId, int offset) {
        return new ToolResultMessage(id, Instant.ofEpochSecond(1775000000 + offset), 45, null,
                callId, "fetchWeb",
                List.of(new cn.edu.scut.chatbot.loop.message.TextContent("结果")), false, false, Map.of())
    }

    def "切点不悬挂：assistant工具调用在压缩区时切点后移越过配对结果"() {
        given: "40 条转录，index 11 是带工具调用的 assistant、index 12 是配对结果，朴素切点 12（悬挂）"
        def transcript = []
        (1..11).each { i -> transcript << user("u" + i, i) }
        transcript << toolCallMessage("a12", "call-X", 12)
        transcript << toolResult("t13", "call-X", 13)
        (14..40).each { i -> transcript << user("u" + i, i) }

        when:
        int cut = compactor.adjustCutToAvoidDanglingPair(transcript, 12)

        then: "切点后移到配对结果之后（对完整留在压缩区）"
        cut == 13
    }

    def "切点本已安全时不动（对完整在近端或无工具对）"() {
        given: "40 条纯用户消息，切点 10 无工具对"
        def transcript = (1..40).collect { i -> user(String.valueOf(900000L + i), i) }

        when:
        int cut = compactor.adjustCutToAvoidDanglingPair(transcript, 10)

        then:
        cut == 10

        and: "工具对完整落在近端的切点（10：assistant 与结果都在近端）同样不动"
        def mixed = []
        (1..11).each { i -> mixed << user("u" + i, i) }
        mixed << toolCallMessage("a12", "call-Y", 12)
        mixed << toolResult("t13", "call-Y", 13)
        (14..40).each { i -> mixed << user("u" + i, i) }

        expect:
        compactor.adjustCutToAvoidDanglingPair(mixed, 10) == 10
    }

    def "反复压缩切点永不落对中间（随机形态多轮）"() {
        given: "60 条：每个 7 的倍数位是工具调用、其后紧跟结果"
        def transcript = []
        int offset = 0
        int toolSeq = 0
        while (transcript.size() < 60) {
            if (transcript.size() > 0 && transcript.size() % 7 == 0) {
                toolSeq++
                transcript << toolCallMessage("a" + toolSeq, "call-" + toolSeq, offset++)
                transcript << toolResult("t" + toolSeq, "call-" + toolSeq, offset++)
            } else {
                transcript << user("u" + transcript.size(), offset++)
            }
        }

        expect: "任意朴素切点调整后都不落对中间"
        (5..55).every { naiveCut ->
            int cut = compactor.adjustCutToAvoidDanglingPair(transcript, naiveCut)
            // 断言：切点前一条若是带工具调用的 assistant（压缩区最后一条），
            // 则其配对结果也必须在压缩区（即 cut 已越过它）
            if (cut > 0) {
                def lastInCompaction = transcript[cut - 1]
                if (lastInCompaction instanceof AssistantMessage) {
                    def assistant = lastInCompaction as AssistantMessage
                    if (assistant.toolCalls() && !assistant.toolCalls().isEmpty()) {
                        boolean pairAlsoInCompaction = transcript[0..<cut].any { entry ->
                            entry instanceof ToolResultMessage
                                    && assistant.toolCalls()*.callId().contains(entry.callId())
                        }
                        if (!pairAlsoInCompaction) {
                            // 配对在近端 → 切点没把整对切齐即悬挂
                            return false
                        }
                    }
                }
            }
            // 断言：近端第一条若是工具结果，其配对调用必须也在近端（不在压缩区）
            if (cut < transcript.size() && transcript[cut] instanceof ToolResultMessage) {
                def result = transcript[cut] as ToolResultMessage
                def callerInCompaction = transcript[0..<cut].any { entry ->
                    entry instanceof AssistantMessage
                            && (entry as AssistantMessage).toolCalls()*.callId().contains(result.callId())
                }
                if (callerInCompaction) {
                    return false
                }
            }
            return true
        }
    }

    def "压缩完成：摘要入转录更新指针 窗口降档quiet落库 发建议沉淀事件"() {
        given: "40 条纯用户消息转录（> 近端保留 30 条，压缩区 10 条）"
        def sessionManager = Mock(SessionManager)
        def sessionMapper = Mock(AgentSessionMapper)
        def events = []
        def pointerValues = []
        def fullCompactor = new TranscriptCompactor(sessionManager, sessionMapper,
                new SeededIdGenerator(), new FixedClock(), { event -> events << event } as org.springframework.context.ApplicationEventPublisher,
                new DeterministicSummaryGenerator(), noopJobWriter(), noopGroupState(), noopTopicLifecycle())
        def transcript = (1..40).collect { i -> user(String.valueOf(900000L + i), i) }
        // 带条目主键的读取形态：entryId 与 messageId 数字刻意不同——断言锚定的是
        // 落库主键（两套号不混用），appendTranscript 返回摘要条目的落库主键
        def items = transcript.withIndex().collect { msg, i ->
            new SessionManager.TranscriptItem(500_000L + i, msg)
        }
        sessionManager.loadTranscriptWithEntryIds(_ as AgentSessionDO) >> items
        def session = new AgentSessionDO()
        session.setId(555L)
        session.setPlatform("qq")
        session.setBotId("10000")
        session.setGroupId("80001")

        when:
        def summary = fullCompactor.compactIfNeeded(session, 99999, 1000)

        then: "新摘要条目追加转录（单条 SummaryEntry）"
        summary != null
        summary.summaryText().contains("讨论参与者")
        1 * sessionManager.appendTranscript(_ as AgentSessionDO, { List batch ->
            batch.size() == 1 && batch[0] instanceof SummaryEntry
        }, null) >> [777_777L]

        and: "指针指向新摘要的落库主键（值经捕获闭包带出，联动断言用普通条件——interaction 参数不能引用 when 局部）"
        1 * sessionMapper.updateSummaryPointer(555L, { Long pointer ->
            pointerValues << pointer
            true
        })
        pointerValues == [777_777L]

        and: "窗口水位降档 quiet 落库（投影读会话档位——多群各持各档）"
        1 * sessionMapper.updateWindowLevel(555L, AgentSessionDO.WINDOW_QUIET)

        and: "发建议沉淀事件：群标识 + 摘要条目锚点 + 压缩体量（消费与否是记忆系统的自由）"
        events.size() == 1
        def event = events[0] as SedimentSuggestionEvent
        event.platform() == "qq"
        event.botId() == "10000"
        event.groupId() == "80001"
        event.summaryEntryId() == summary.messageId()
        event.compactedCount() == 10
    }

    def "未到触发线不压缩：不降档不发事件不动转录"() {
        given:
        def sessionManager = Mock(SessionManager)
        def sessionMapper = Mock(AgentSessionMapper)
        def events = []
        def fullCompactor = new TranscriptCompactor(sessionManager, sessionMapper,
                new SeededIdGenerator(), new FixedClock(), { event -> events << event } as org.springframework.context.ApplicationEventPublisher,
                new DeterministicSummaryGenerator(), noopJobWriter(), noopGroupState(), noopTopicLifecycle())
        def session = new AgentSessionDO()
        session.setId(555L)
        session.setPlatform("qq")
        session.setBotId("10000")
        session.setGroupId("80001")

        when: "投影量未超触发线"
        def summary = fullCompactor.compactIfNeeded(session, 500, 1000)

        then:
        summary == null
        events.isEmpty()
        0 * sessionMapper.updateWindowLevel(_, _)
        0 * sessionMapper.updateSummaryPointer(_, _)
        0 * sessionManager.appendTranscript(_, _, _)
    }
}
