// 守护纪律：预取是增强信息——失败安静跳过不阻塞主流程（T095）；
// 快照纪律：卡片条目只追加转录尾部，不打断进行中 Run
package cn.edu.scut.chatbot.context.link

import cn.edu.scut.chatbot.bootstrap.SystemParamService
import cn.edu.scut.chatbot.engine.session.AgentSessionDO
import cn.edu.scut.chatbot.engine.session.SessionManager
import cn.edu.scut.chatbot.fixtures.FixedClock
import cn.edu.scut.chatbot.fixtures.SeededIdGenerator
import spock.lang.Specification

class LinkPrefetcherTest extends Specification {

    def sessionManager = Mock(SessionManager)
    def systemParams = Mock(SystemParamService)

    LinkPrefetcher prefetcher

    AgentSessionDO session = sessionOf(888L)

    /**
     * 二级摘要被动升级的纯单测受限说明：SafeFetcher 在构造器内实例化（含 SSRF 全防线，
     * 仅 https + 拒绝回环/私网目标），无法注入可控假抓取器，回环测试服务器也被纪律拦截。
     * 本类只覆盖"预取失败安静跳过"与"无 URL 不动"的纯逻辑面；
     * 卡片质量不足 → 二级升级的完整链路依赖真实出站抓取，归 IT/联调覆盖。
     */
    def setup() {
        systemParams.getInt("context.card_summary_chars", 80) >> 80
        systemParams.getLong("context.link_prefetch_timeout_millis", 3000) >> 500
        systemParams.getLong("context.link_max_response_bytes", 262144) >> 262144
        and: "构造必须晚于打桩（构造器内读取超时参数，缺省 0 会造出非法 Duration）"
        prefetcher = new LinkPrefetcher(sessionManager,
                new SeededIdGenerator(), new FixedClock(), systemParams)
    }

    def "文本无URL或为null时不动转录（没有可预取对象）"() {
        when:
        prefetcher.prefetchAndAppend(session, null, "10086")
        prefetcher.prefetchAndAppend(session, "今天聊点别的", "10086")

        then:
        0 * sessionManager.appendTranscript(_, _, _)
    }

    def "回环目标被SSRF防线拦截的预取安静跳过（增强信息不阻塞登记主流程）"() {
        given: "群友贴了条内网地址（https 回环目标——SafeFetcher 纪律拦截的典型形态）"
        def url = "https://127.0.0.1:9443/internal/page"

        when:
        prefetcher.prefetchAndAppend(session, "看看 " + url, "10086")

        then: "被拦截的预取安静跳过：无卡片条目追加、无异常外溢"
        0 * sessionManager.appendTranscript(_, _, _)
        notThrown(Exception)
    }

    def "一段消息含多个URL时逐个尝试 失败互不影响"() {
        when:
        prefetcher.prefetchAndAppend(session,
                "链接一 https://127.0.0.1:9443/x 链接二 https://127.0.0.2:9443/y", "10086")

        then:
        notThrown(Exception)
        0 * sessionManager.appendTranscript(_, _, _)
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
