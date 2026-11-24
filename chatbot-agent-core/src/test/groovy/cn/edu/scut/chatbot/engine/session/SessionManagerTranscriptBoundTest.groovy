// 守护纪律：源头截断第三道防线——入库统一兜底（生成侧产出的漏网超长在入库前被裁）；
// 群友消息的宽松截断在写入侧已完成，此处只兜模型/工具产出
package cn.edu.scut.chatbot.engine.session

import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.SeededIdGenerator
import cn.edu.scut.chatbot.loop.message.AssistantMessage
import cn.edu.scut.chatbot.loop.message.ImageContent
import cn.edu.scut.chatbot.loop.message.TextContent
import cn.edu.scut.chatbot.loop.message.ToolResultMessage
import spock.lang.Specification

import java.time.Instant

class SessionManagerTranscriptBoundTest extends Specification {

    def sessionMapper = Mock(AgentSessionMapper)
    TranscriptCodec codec = new TranscriptCodec()
    FixedClock clock = new FixedClock()

    SessionManager manager = new SessionManager(sessionMapper, codec,
            new SeededIdGenerator(), clock, 60000L)

    AgentSessionDO session = sessionOf(555L)

    /** 捕获入库的转录条目行（单实参方法的响应闭包以单参收实参数组——取 args[0]） */
    private List capturedEntries() {
        def entries = []
        1 * sessionMapper.insertTranscriptBatch(_) >> { args ->
            entries.addAll(args[0])
            1
        }
        return entries
    }

    def "超长assistant文本入库前截断兜底（构造截断副本 非原地改）"() {
        given: "100 行 × 100 字中文 ≈ 30KB（远超 6KB 条目上限，单行不超 400）"
        def hugeText = (["中".repeat(100)] * 100).join("\n")
        def message = new AssistantMessage("a1", Instant.ofEpochMilli(1775001600000L), 60,
                null, hugeText, List.of(), "stop", AssistantMessage.TokenUsage.ZERO)
        def entries = capturedEntries()

        when:
        manager.appendTranscript(session, List.of(message), 42L)

        then: "入库的是截断副本：字节受控且带自描述标注"
        entries.size() == 1
        def decoded = codec.decode(entries[0].payload, entries[0].kind) as AssistantMessage
        decoded.textContent().contains("[已截断")
        decoded.textContent().contains("字节触限")
        decoded.textContent().getBytes().length < hugeText.getBytes().length
        entries[0].runId == 42L
        entries[0].sessionId == 555L
        and: "原消息对象不被改动（条目不可变纪律）"
        message.textContent() == hugeText
    }

    def "超长工具结果文本段截断 图片引用段原样保留"() {
        given:
        def hugeText = (["果".repeat(100)] * 100).join("\n")
        def message = new ToolResultMessage("t1", Instant.ofEpochMilli(1775001600000L), 45,
                null, "call-1", "fetchWeb",
                List.of(new TextContent(hugeText), new ImageContent("stored://x.png")),
                false, false, Map.of())
        def entries = capturedEntries()

        when:
        manager.appendTranscript(session, List.of(message), null)

        then:
        def decoded = codec.decode(entries[0].payload, entries[0].kind) as ToolResultMessage
        def textContent = decoded.content()[0] as TextContent
        textContent.text().contains("[已截断")
        textContent.text().length() < hugeText.length()
        and: "非文本段保留原形态"
        (decoded.content()[1] as ImageContent).storedRef() == "stored://x.png"
    }

    def "正常长度条目原样入库（兜底不误伤）"() {
        given:
        def message = new AssistantMessage("a2", Instant.ofEpochMilli(1775001600000L), 60,
                null, "正常长度的回复", List.of(), "stop", AssistantMessage.TokenUsage.ZERO)
        def entries = capturedEntries()

        when:
        manager.appendTranscript(session, List.of(message), null)

        then:
        def decoded = codec.decode(entries[0].payload, entries[0].kind) as AssistantMessage
        decoded.textContent() == "正常长度的回复"
        !decoded.textContent().contains("已截断")
    }

    private AgentSessionDO sessionOf(long id) {
        AgentSessionDO session = new AgentSessionDO()
        session.setId(id)
        session.setPlatform("qq")
        session.setBotId("10000")
        session.setGroupId("80001")
        return session
    }
}
