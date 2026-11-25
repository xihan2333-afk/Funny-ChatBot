// 守护纪律：fetchWeb 阅读请求发往 summarize 槽位、阅读失败降级启发式、
// 内网目标负向仍被拒（SSRF 防线不变）（spec US7 场景 5；quickstart 场景 F）
package cn.edu.scut.chatbot.tool

import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.exception.NonRetryableException
import cn.edu.scut.chatbot.infrastructure.safefetcher.SafeFetcher
import cn.edu.scut.chatbot.llm.gateway.CallOnceResult
import cn.edu.scut.chatbot.llm.gateway.ModelGateway
import cn.edu.scut.chatbot.loop.AgentToolResult
import cn.edu.scut.chatbot.loop.ToolInvocation
import cn.edu.scut.chatbot.tool.builtin.FetchWebTool
import spock.lang.Specification

class FetchWebToolTest extends Specification {

    def systemParams = Mock(SystemParamService) {
        getLong(_, _) >> 3000L
    }
    def gateway = Mock(ModelGateway)
    def safeFetcher = Mock(SafeFetcher)
    def tool = new FetchWebTool(systemParams, gateway, safeFetcher)

    def invocation(String url) {
        return new ToolInvocation('{"url":"' + url + '"}', "qq", "10000", "80001",
                cn.edu.scut.chatbot.loop.RunConfig.ExecutionForm.TURN, 7001L, null, "fetchWeb")
    }

    def "阅读请求发往 summarize 槽位、工具结果含模型摘要"() {
        given:
        safeFetcher.fetch("https://example.com/article") >> new SafeFetcher.FetchedBody(
                "<html><meta property=\"og:title\" content=\"测试文章\"/>" +
                        "<body>正文内容关于广州天气</body></html>", false)
        gateway.callOnce("summarize", _, _, _, _) >> CallOnceResult.success("一篇关于广州天气的文章摘要", null)

        when:
        AgentToolResult result = tool.execute(invocation("https://example.com/article"))

        then:
        !result.isError()
        result.content()[0].text().contains("文章摘要")
    }

    def "阅读失败降级为启发式摘要（og:title 仍可用）"() {
        given:
        safeFetcher.fetch("https://example.com/x") >> new SafeFetcher.FetchedBody(
                "<html><meta property=\"og:title\" content=\"降级标题\"/><body>降级正文</body></html>", false)
        gateway.callOnce("summarize", _, _, _, _) >> CallOnceResult.failure("llm.config_missing", "槽位未绑定")

        when:
        AgentToolResult result = tool.execute(invocation("https://example.com/x"))

        then:
        !result.isError()
        result.content()[0].text().contains("降级标题")
    }

    def "内网目标负向仍被拒（SSRF 防线不变——isError 业务分支）"() {
        given:
        safeFetcher.fetch("https://192.168.1.1/internal") >> {
            throw new NonRetryableException("ssrf.blocked", "内网目标被 SSRF 防线拦截")
        }

        when:
        AgentToolResult result = tool.execute(invocation("https://192.168.1.1/internal"))

        then:
        result.isError()
        0 * gateway._
    }
}
