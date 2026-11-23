// 守护纪律：压缩切点永不落工具对中间（spec US7 验收场景 2）；五段摘要结构；增量合并
package cn.edu.scut.chatbot.context.compaction

import cn.edu.scut.chatbot.loop.message.AgentMessage
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import cn.edu.scut.chatbot.loop.message.ToolResultMessage
import cn.edu.scut.chatbot.loop.message.UserMessage
import spock.lang.Specification

import java.time.Instant

class CompactionTest extends Specification {

    def compactor = new TranscriptCompactor(null, null, null, null)

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
        def transcript = (1..40).collect { i -> user("u" + i, i) }

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
}
