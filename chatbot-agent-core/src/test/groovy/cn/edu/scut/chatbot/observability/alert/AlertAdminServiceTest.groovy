// 守护纪律：builtin 规则保护与规则校验（observe-query 契约第 2 节、spec US3 场景 5、T033）——
// builtin 允许停用不允许删除、metric 限内置枚举、名字唯一、变更留审计
package cn.edu.scut.chatbot.observability.alert

import cn.edu.scut.chatbot.bootstrap.JsonMappers
import cn.edu.scut.chatbot.security.audit.SecurityEventKind
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder
import spock.lang.Specification

class AlertAdminServiceTest extends Specification {

    AlertRuleMapper ruleMapper = Mock()
    SecurityEventRecorder recorder = Mock()

    MetricProbe knownProbe = Mock() {
        metric() >> "turn.failRate"
    }

    AlertAdminService service

    def setup() {
        service = new AlertAdminServiceImpl(ruleMapper, recorder,
                JsonMappers.shared(), List.of(knownProbe))
    }

    private def upsert(String name = "自定义失败率", String metric = "turn.failRate",
                       String threshold = '{"op":"gt","value":0.05,"suppressMin":30}',
                       String level = "warn", Boolean enabled = null) {
        return new AlertAdminService.RuleUpsertRequest(
                name, metric, 30, threshold, level, enabled)
    }

    private AlertRuleDO builtinRule() {
        def r = new AlertRuleDO()
        r.setId(9L)
        r.setName("Turn 失败率")
        r.setMetric("turn.failRate")
        r.setWindowMin(60)
        r.setThreshold('{"op":"gt","value":0.10,"suppressMin":30}')
        r.setLevel("warn")
        r.setEnabled(true)
        r.setBuiltin(true)
        return r
    }

    def "builtin 删除被拒绝（允许停用不允许删除）"() {
        given:
        ruleMapper.selectById(9L) >> builtinRule()

        when:
        service.deleteRule(9L)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("不允许删除")
        0 * ruleMapper.deleteById(_)
    }

    def "builtin 停用走更新不受影响"() {
        given:
        ruleMapper.selectById(9L) >> builtinRule()
        def stop = upsert("Turn 失败率", "turn.failRate",
                '{"op":"gt","value":0.10,"suppressMin":30}', "warn", false)

        when:
        service.updateRule(9L, stop)

        then:
        1 * ruleMapper.updateRule({ it.enabled == false && it.id == 9L })
        1 * recorder.record({ k -> k == SecurityEventKind.ADMIN_OPERATE }, _, _, _)
    }

    def "builtin 规则 metric 不可改"() {
        given:
        ruleMapper.selectById(9L) >> builtinRule()

        when:
        service.updateRule(9L, upsert("Turn 失败率", "config.missing",
                '{"op":"gt","value":0,"suppressMin":30}', "critical", null))

        then:
        thrown(IllegalArgumentException)
        0 * ruleMapper.updateRule(_)
    }

    def "自定义规则删除放行且留审计"() {
        given:
        def custom = builtinRule()
        custom.setBuiltin(false)
        ruleMapper.selectById(5L) >> custom

        when:
        service.deleteRule(5L)

        then:
        1 * ruleMapper.deleteById(5L)
        1 * recorder.record({ k -> k == SecurityEventKind.ADMIN_OPERATE },
                _, { d -> d.action == "delete" && d.ruleId == 5L }, _)
    }

    def "自定义规则 metric 限内置枚举（无探针实现的 metric 拒绝）"() {
        when:
        service.createRule(upsert("自定义", "no.such.metric"))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("内置枚举")
        0 * ruleMapper.insertRule(_)
    }

    def "规则名重复拒绝"() {
        given:
        ruleMapper.selectByName("自定义失败率") >> builtinRule()

        when:
        service.createRule(upsert())

        then:
        thrown(IllegalArgumentException)
    }

    def "level 非法拒绝"() {
        when:
        service.createRule(upsert("规则", "turn.failRate",
                '{"op":"gt","value":0.05,"suppressMin":30}', "fatal", null))

        then:
        thrown(IllegalArgumentException)
    }

    def "threshold JSON 非法拒绝"() {
        when:
        service.createRule(upsert("规则", "turn.failRate", "{broken", "warn", null))

        then:
        thrown(IllegalArgumentException)
    }

    def "新建自定义规则 builtin=false 默认 enabled 且留审计"() {
        given:
        ruleMapper.selectByName(_) >> null

        when:
        def id = service.createRule(upsert())

        then:
        id == 99L
        1 * ruleMapper.insertRule({ r -> !r.builtin && r.enabled }) >> { AlertRuleDO r ->
            r.setId(99L)
            return 1
        }
        1 * recorder.record({ k -> k == SecurityEventKind.ADMIN_OPERATE },
                _, { d -> d.action == "create" }, _)
    }
}
