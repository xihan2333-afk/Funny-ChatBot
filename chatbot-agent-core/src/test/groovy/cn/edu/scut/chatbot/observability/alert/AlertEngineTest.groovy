// 守护纪律：告警判定链的核心语义（observe-query 契约第 2 节、T033）——
// 阈值命中落库、级别透传、paramKey 经 system_param 解析、同规则同维度窗口内抑制、
// 不同维度各自告警、空样本不判定、单规则异常不外溢
package cn.edu.scut.chatbot.observability.alert

import cn.edu.scut.chatbot.bootstrap.JsonMappers
import cn.edu.scut.chatbot.bootstrap.SystemParamService
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AlertEngineTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-26T08:00:00Z")
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai")

    AlertRuleMapper ruleMapper = Mock()
    AlertNotificationMapper notificationMapper = Mock()
    SystemParamService params = Mock()
    Clock clock = Clock.fixed(NOW, ZONE)

    MetricProbe probe = Mock()
    AlertEngine engine

    def setup() {
        probe.metric() >> "turn.failRate"
        engine = new AlertEngine(ruleMapper, notificationMapper, params,
                JsonMappers.shared(), clock, List.of(probe))
    }

    private AlertRuleDO rule(String thresholdJson, String level = "warn") {
        def r = new AlertRuleDO()
        r.setId(1L)
        r.setName("Turn 失败率")
        r.setMetric("turn.failRate")
        r.setWindowMin(60)
        r.setThreshold(thresholdJson)
        r.setLevel(level)
        r.setEnabled(true)
        r.setBuiltin(true)
        return r
    }

    private AlertNotificationDO notification(String summary, String createdAtIso) {
        def n = new AlertNotificationDO()
        n.setRuleId(1L)
        n.setRuleName("Turn 失败率")
        n.setLevel("warn")
        n.setSummary(summary)
        n.setCreatedAt(LocalDateTime.parse(createdAtIso))
        return n
    }

    def "命中落库：值超阈值 通知带规则名与级别（级别映射来自规则行）"() {
        given:
        def r = rule('{"op":"gt","value":0.10,"suppressMin":30}')
        ruleMapper.selectEnabled() >> List.of(r)
        notificationMapper.selectRecentByRule(1L, _) >> List.of()
        probe.sample(r, NOW) >> Optional.of(
                new AlertSample(0.15, "近 60 分钟 Turn 失败率 15.0%", null, "trace-1"))

        when:
        engine.evaluateOnce()

        then:
        1 * notificationMapper.insert({ n ->
            n.ruleId == 1L && n.ruleName == "Turn 失败率" && n.level == "warn"
                    && n.summary == "近 60 分钟 Turn 失败率 15.0%"
                    && n.traceId == "trace-1" && !n.readFlag
        })
    }

    def "paramKey 阈值经 system_param 解析（日成本超限形态）"() {
        given:
        def r = rule('{"op":"gt","paramKey":"security.breaker.globalDailyLimit","suppressMin":120}')
        ruleMapper.selectEnabled() >> List.of(r)
        notificationMapper.selectRecentByRule(1L, _) >> List.of()
        probe.sample(r, NOW) >> Optional.of(new AlertSample(50.0, "当日成本 50.0000", null, null))
        params.getLong("security.breaker.globalDailyLimit", 0) >> 30L

        when:
        engine.evaluateOnce()

        then:
        1 * notificationMapper.insert(_)
    }

    def "窗口内抑制：同维度近期通知存在不再落库"() {
        given:
        def r = rule('{"op":"gt","value":0.10,"suppressMin":60}')
        ruleMapper.selectEnabled() >> List.of(r)
        // 10 分钟前落过同规则通知（无维度标记——空维度探针）
        notificationMapper.selectRecentByRule(1L, _) >> List.of(
                notification("近 60 分钟 Turn 失败率 15.0%", "2026-11-26T15:55:00"))
        probe.sample(r, NOW) >> Optional.of(new AlertSample(0.2, "又超了", null, null))

        when:
        engine.evaluateOnce()

        then:
        0 * notificationMapper.insert(_)
    }

    def "同维度抑制不同维度各自告警（越权 userId 维度）"() {
        given:
        def r = rule('{"op":"gte","value":10,"suppressMin":60}')
        ruleMapper.selectEnabled() >> List.of(r)
        // userA 已在窗口内告警过（summary 尾部维度标记）
        notificationMapper.selectRecentByRule(1L, _) >> List.of(
                notification("用户 userA 窗口内 Mandate 越权被拒 12 次 |dim=userA",
                        "2026-11-26T15:50:00"))
        // 本次命中的是 userB
        probe.sample(r, NOW) >> Optional.of(
                new AlertSample(11, "用户 userB 窗口内越权被拒 11 次", "userB", null))

        when:
        engine.evaluateOnce()

        then:
        1 * notificationMapper.insert({ n -> n.summary.endsWith(" |dim=userB") })
    }

    def "空样本不判定（窗口无数据不是告警）"() {
        given:
        def r = rule('{"op":"gt","value":0.10,"suppressMin":30}')
        ruleMapper.selectEnabled() >> List.of(r)
        probe.sample(r, NOW) >> Optional.empty()

        when:
        engine.evaluateOnce()

        then:
        0 * notificationMapper.insert(_)
    }

    def "值不达阈值不落库"() {
        given:
        def r = rule('{"op":"gt","value":0.10,"suppressMin":30}')
        ruleMapper.selectEnabled() >> List.of(r)
        probe.sample(r, NOW) >> Optional.of(new AlertSample(0.05, "5%", null, null))

        when:
        engine.evaluateOnce()

        then:
        0 * notificationMapper.insert(_)
    }

    def "无探针承接的 metric 跳过不炸"() {
        given:
        def r = rule('{"op":"gt","value":0,"suppressMin":30}')
        r.setMetric("unknown.metric")
        ruleMapper.selectEnabled() >> List.of(r)

        when:
        engine.evaluateOnce()

        then:
        0 * notificationMapper.insert(_)
        noExceptionThrown()
    }

    def "单条探针异常不外溢且不影响其它规则"() {
        given:
        def bad = rule('{"op":"gt","value":0,"suppressMin":30}')
        bad.setId(1L)
        def good = rule('{"op":"gt","value":0,"suppressMin":30}')
        good.setId(2L)
        good.setMetric("turn.failRate")
        ruleMapper.selectEnabled() >> List.of(bad, good)
        // 同一探针：id=1 抛异常，id=2 正常取样命中
        probe.sample({ r -> r.id == 1L }, _) >> { throw new RuntimeException("db down") }
        probe.sample({ r -> r.id == 2L }, _) >> Optional.of(new AlertSample(1, "命中", null, null))
        notificationMapper.selectRecentByRule(2L, _) >> List.of()

        when:
        engine.evaluateOnce()

        then:
        1 * notificationMapper.insert({ n -> n.ruleId == 2L })
        noExceptionThrown()
    }

    def "summary 超长截断到 512（表列宽保护）"() {
        given:
        def r = rule('{"op":"gt","value":0,"suppressMin":30}')
        ruleMapper.selectEnabled() >> List.of(r)
        notificationMapper.selectRecentByRule(1L, _) >> List.of()
        def longSummary = "超长" * 400
        probe.sample(r, NOW) >> Optional.of(new AlertSample(1, longSummary, null, null))

        when:
        engine.evaluateOnce()

        then:
        1 * notificationMapper.insert({ n -> n.summary.length() == 512 })
    }
}
