// 守护纪律：十二条内置规则逐条探针取数口径（T033，observe-query 契约第 3 节表格）——
// 各探针 value 形态、维度、空样本语义（窗口无数据不判定）不随重构漂移
package cn.edu.scut.chatbot.observability.alert

import cn.edu.scut.chatbot.engine.intake.DecisionLayerCount
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper
import cn.edu.scut.chatbot.engine.turn.TurnMapper
import cn.edu.scut.chatbot.engine.turn.TurnStatusCount
import cn.edu.scut.chatbot.llm.slot.CredentialMapper
import cn.edu.scut.chatbot.llm.slot.LlmCallLogAggregation
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper
import cn.edu.scut.chatbot.llm.slot.OwnerAvailableCount
import cn.edu.scut.chatbot.observability.bridge.ExportHealthMetrics
import cn.edu.scut.chatbot.security.audit.SecurityEventMapper
import cn.edu.scut.chatbot.security.audit.UserDeniedCount
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MetricProbesTest extends Specification {

    static final Instant NOW = Instant.parse("2026-11-26T08:00:00Z")
    static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"))

    private AlertRuleDO rule(String metric, int windowMin = 60) {
        def r = new AlertRuleDO()
        r.setId(1L)
        r.setName(metric)
        r.setMetric(metric)
        r.setWindowMin(windowMin)
        r.setThreshold('{"op":"gt","value":0,"suppressMin":30}')
        r.setLevel("warn")
        r.setEnabled(true)
        r.setBuiltin(true)
        return r
    }

    // ===== 1 槽位缺失 =====

    def "config.missing：窗口内有 config_missing 失败 Turn 取样为计数 无则空"() {
        given:
        def mapper = Mock(TurnMapper)
        def probe = new ConfigMissingProbe(mapper, CLOCK)
        def r = rule("config.missing", 5)

        when:
        mapper.countByErrorCodePrefix("llm.config_missing", _, _) >>> [3L, 0L]
        def hit = probe.sample(r, NOW)
        def miss = probe.sample(r, NOW)

        then:
        hit.present
        hit.get().value() == 3
        !miss.present
    }

    // ===== 2 凭证全冷却 =====

    def "credential.exhausted：全冷却供应商 value=0 且维度为该供应商"() {
        given:
        def mapper = Mock(CredentialMapper)
        def probe = new CredentialExhaustedProbe(mapper)
        mapper.countAvailableByProvider() >> List.of(available("openai", 2L), available("deepseek", 0L))

        when:
        def result = probe.sample(rule("credential.exhausted"), NOW)

        then:
        result.present
        result.get().value() == 0
        result.get().dimensionKey() == "deepseek"
    }

    def "credential.exhausted：全部有可用 key 时 value 取最小（不命中 eq 0）"() {
        given:
        def mapper = Mock(CredentialMapper)
        def probe = new CredentialExhaustedProbe(mapper)
        mapper.countAvailableByProvider() >> List.of(available("openai", 2L), available("deepseek", 1L))

        expect:
        probe.sample(rule("credential.exhausted"), NOW).get().value() == 1
    }

    def "credential.exhausted：无供应商数据为空样本"() {
        given:
        def mapper = Mock(CredentialMapper)
        def probe = new CredentialExhaustedProbe(mapper)
        mapper.countAvailableByProvider() >> List.of()

        expect:
        !probe.sample(rule("credential.exhausted"), NOW).present
    }

    // ===== 3 Turn 失败率 =====

    def "turn.failRate：FAILED 占比分母为全部状态 无 Turn 空样本"() {
        given:
        def mapper = Mock(TurnMapper)
        def probe = new TurnFailRateProbe(mapper, CLOCK)
        def r = rule("turn.failRate", 60)
        mapper.countByStatus(_, _, null) >>> [
                List.of(status("FAILED", 15), status("SETTLED", 80), status("SILENT", 5)),
                List.of()
        ]

        when:
        def hit = probe.sample(r, NOW)
        def miss = probe.sample(r, NOW)

        then:
        hit.present
        hit.get().value() == 0.15
        !miss.present
    }

    // ===== 4 日成本超限 =====

    def "cost.dailyOver：当日 0 点起 is_replay=0 成本合计（恒有样本 成本 0 不命中 gt）"() {
        given:
        def mapper = Mock(LlmCallLogMapper)
        def probe = new CostDailyOverProbe(mapper, CLOCK)
        def totals = new LlmCallLogAggregation()
        totals.setCostAmount(new BigDecimal("12.5"))
        mapper.selectTokenTotals(_, null, null) >> totals

        when:
        def result = probe.sample(rule("cost.dailyOver", 60), NOW)

        then:
        result.present
        result.get().value() == 12.5
        result.get().summary().contains("12.5")
    }

    // ===== 5 L1 保守降级率 =====

    def "l1.conservativeRate：degrade 占 L1+degrade 无判定空样本"() {
        given:
        def mapper = Mock(TriggerEventMapper)
        def probe = new L1ConservativeRateProbe(mapper, CLOCK)
        def r = rule("l1.conservativeRate", 60)
        mapper.countDecisionByWindow(_, _, null) >>> [
                List.of(layer("L1", "accept", 80), layer("degrade", "ignore", 20)),
                List.of()
        ]

        when:
        def hit = probe.sample(r, NOW)
        def miss = probe.sample(r, NOW)

        then:
        hit.present
        hit.get().value() == 0.2
        !miss.present
    }

    // ===== 6 导出管道异常 =====

    def "observe.exportStuck：最近失败落在窗口内取样 1 否则空"() {
        given:
        def health = Mock(ExportHealthMetrics)
        def probe = new ExportStuckProbe(health)
        def r = rule("observe.exportStuck", 5)
        // 三次取样依次返回：窗口内 / 窗口外 / 从未失败
        health.lastExportFailureAtMs() >>> [NOW.toEpochMilli() - 60_000,
                                            NOW.toEpochMilli() - 3600_000,
                                            0L]
        health.exportFailureCount() >> 4

        when:
        def hit = probe.sample(r, NOW)
        def outside = probe.sample(r, NOW)
        def never = probe.sample(r, NOW)

        then:
        hit.present
        hit.get().value() == 1
        !outside.present
        !never.present
    }

    // ===== 7 主动行为异常 =====

    def "social.initiativeDrop：近期成功率对 7 日基线的比值 基线无样本空"() {
        given:
        def mapper = Mock(TriggerEventMapper)
        def probe = new InitiativeDropProbe(mapper, CLOCK)
        def r = rule("social.initiativeDrop", 1440)

        // 每次取样两次查询（先近期后基线）：第一轮近期低基线高；第二轮基线空
        mapper.countInternalDecision("silenceBreak", _, _, null) >>> [
                List.of(layer(null, "accept", 2), layer(null, "ignore", 8)),
                List.of(layer(null, "accept", 70), layer(null, "ignore", 30)),
                List.of(layer(null, "accept", 5), layer(null, "ignore", 5)),
                List.of()
        ]

        when:
        def result = probe.sample(r, NOW)
        def noBaseline = probe.sample(r, NOW)

        then:
        result.present
        Math.abs(result.get().value() - 0.2 / 0.7) < 1e-9   // 近期 0.2 / 基线 0.7
        !noBaseline.present
    }

    // ===== 8 拦截率突增 =====

    def "security.eventBurst：窗口计数对 7 日小时均值倍数 基线为零空样本"() {
        given:
        def mapper = Mock(SecurityEventMapper)
        def probe = new SecurityEventBurstProbe(mapper, CLOCK)
        def r = rule("security.eventBurst", 60)
        // 每次取样两次查询（先窗口后基线）：第一轮 30 条 vs 840 条基线；第二轮基线 0
        mapper.countAllBetween(_, _) >>> [30L, 840L, 30L, 0L]

        when:
        def result = probe.sample(r, NOW)
        def noBaseline = probe.sample(r, NOW)

        then:
        result.present
        result.get().value() == 6.0
        !noBaseline.present
    }

    // ===== 9 越权尝试集中 =====

    def "security.mandateBurst：最高计数用户 value=次数 维度=userId 无数据空"() {
        given:
        def mapper = Mock(SecurityEventMapper)
        def probe = new MandateBurstProbe(mapper, CLOCK)
        def top = new UserDeniedCount()
        top.setUserId("userA")
        top.setCount(12)
        mapper.selectTopMandateUser(_) >>> [top, null]

        when:
        def hit = probe.sample(rule("security.mandateBurst", 60), NOW)
        def miss = probe.sample(rule("security.mandateBurst", 60), NOW)

        then:
        hit.present
        hit.get().value() == 12
        hit.get().dimensionKey() == "userA"
        !miss.present
    }

    // ===== 10/11/12 安全事件三计数探针 =====

    def "security.sandboxAbnormal：窗口计数 空窗口空样本"() {
        given:
        def mapper = Mock(SecurityEventMapper)
        def probe = new SandboxAbnormalProbe(mapper, CLOCK)
        mapper.countByKindSince("sandboxAbnormal", _) >>> [2L, 0L]

        expect:
        probe.sample(rule("security.sandboxAbnormal"), NOW).get().value() == 2
        !probe.sample(rule("security.sandboxAbnormal"), NOW).present
    }

    def "breaker.tripped：熔断事件计数"() {
        given:
        def mapper = Mock(SecurityEventMapper)
        def probe = new BreakerTrippedProbe(mapper, CLOCK)
        mapper.countByKindSince("circuitBreak", _) >> 1L

        expect:
        probe.sample(rule("breaker.tripped"), NOW).get().value() == 1
    }

    def "credential.authFail：凭证失效事件计数"() {
        given:
        def mapper = Mock(SecurityEventMapper)
        def probe = new CredentialAuthFailProbe(mapper, CLOCK)
        mapper.countByKindSince("credentialInvalid", _) >> 3L

        expect:
        probe.sample(rule("credential.authFail"), NOW).get().value() == 3
    }

    // ===== 构造助手 =====

    private OwnerAvailableCount available(String code, long count) {
        def a = new OwnerAvailableCount()
        a.setOwnerCode(code)
        a.setAvailableCount(count)
        return a
    }

    private TurnStatusCount status(String status, long count) {
        def c = new TurnStatusCount()
        c.setStatus(status)
        c.setCount(count)
        return c
    }

    private DecisionLayerCount layer(String layer, String decision, long count) {
        def c = new DecisionLayerCount()
        c.setDecisionLayer(layer)
        c.setDecision(decision)
        c.setCount(count)
        return c
    }
}
