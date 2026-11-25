// 守护纪律：五段摘要文本来自 summarize 槽位真模型（非确定性拼装）、
// 模型失败降级确定性拼装（压缩是关键路径不失效）（spec US7 场景 7）
package cn.edu.scut.chatbot.context

import cn.edu.scut.chatbot.context.compaction.DeterministicSummaryGenerator
import cn.edu.scut.chatbot.context.compaction.ModelSummarizer
import cn.edu.scut.chatbot.contract.message.TextPart
import cn.edu.scut.chatbot.llm.gateway.CallOnceResult
import cn.edu.scut.chatbot.llm.gateway.ModelGateway
import cn.edu.scut.chatbot.loop.message.AgentMessage
import cn.edu.scut.chatbot.loop.message.UserMessage
import spock.lang.Specification

class ModelSummarizerTest extends Specification {

    def gateway = Mock(ModelGateway)
    def summarizer = new ModelSummarizer(gateway)

    def userMessage(String sender, String text) {
        return new UserMessage("m-" + System.nanoTime(), java.time.Instant.now(), 60, null,
                sender, List.of(new TextPart(TextPart.TYPE_TEXT, text)))
    }

    def "五段摘要来自 summarize 槽位模型产出（非确定性拼装）"() {
        given:
        def entries = [userMessage("u1", "广州今天好热"), userMessage("u2", "是啊三十多度")]
        def modelText = "讨论参与者：u1、u2\n主要话题：广州天气炎热\n结论与共识：都觉得很热\n情绪氛围：轻松吐槽\n待办与悬念：无"
        gateway.callOnce("summarize", _, _, _, _) >> CallOnceResult.success(modelText, null)

        when:
        def summary = summarizer.generate(null, entries)

        then:
        summary == modelText
    }

    def "旧摘要并入模型输入（增量合并不丢历史）"() {
        given:
        gateway.callOnce("summarize", _, _, _, _) >> { args ->
            def messages = (List) args[3]
            assert messages[0].text().contains("早前摘要") && messages[0].text().contains("上次的话题")
            return CallOnceResult.success("新摘要", null)
        }

        when:
        summarizer.generate("上次的话题", [userMessage("u1", "新内容")])

        then:
        noExceptionThrown()
    }

    def "模型失败降级确定性拼装（压缩关键路径不失效）"() {
        given:
        def entries = [userMessage("u1", "内容")]
        gateway.callOnce("summarize", _, _, _, _) >> CallOnceResult.failure("llm.config_missing", "槽位未绑定")

        when:
        def summary = summarizer.generate(null, entries)

        then:
        summary.contains("讨论参与者")
        summary.contains("确定性拼装")
    }

    def "模型调用异常同样降级"() {
        given:
        gateway.callOnce("summarize", _, _, _, _) >> { throw new IllegalStateException("boom") }

        when:
        def summary = summarizer.generate(null, [userMessage("u1", "内容")])

        then:
        new DeterministicSummaryGenerator().generate(null, List.<AgentMessage> of()) != summary || summary != null
        summary.contains("讨论参与者")
    }
}
