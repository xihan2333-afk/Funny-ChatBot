// 守护纪律：配置三层归层——运行层参数热变更生效、缺省值兜底（宪法「配置三层」节）
package cn.edu.scut.chatbot.bootstrap

import spock.lang.Specification

class SystemParamServiceTest extends Specification {

    def mapper = Mock(SystemParamMapper)
    def service = new SystemParamService(mapper)

    def "表有值用表值 表缺行走调用方缺省"() {
        given:
        mapper.selectAll() >> [param("worker.max_concurrency", "4")]

        when:
        service.refresh()

        then:
        service.getString("worker.max_concurrency", "2") == "4"
        service.getInt("worker.max_concurrency", 2) == 4
        service.getInt("gate.cooldown_millis", 8000) == 8000
        service.getString("not.exists", "fallback") == "fallback"
    }

    def "表值不是合法数字时走缺省并告警不抛异常"() {
        given:
        mapper.selectAll() >> [param("turn.queue_timeout_millis", "abc")]

        when:
        service.refresh()
        def value = service.getInt("turn.queue_timeout_millis", 60000)

        then:
        value == 60000
        noExceptionThrown()
    }

    def "热更新后新值立即可读"() {
        given: "装载次数驱动的桩——第 1 次返回旧值、之后返回新值（模拟管理面改表）"
        def loadCount = 0
        mapper.selectAll() >> {
            loadCount++
            if (loadCount == 1) {
                return [param("gate.rate_limit_per_minute", "6")]
            }
            return [param("gate.rate_limit_per_minute", "3")]
        }
        service.refresh()
        assert service.getInt("gate.rate_limit_per_minute", 6) == 6

        when: "管理面改表后刷新"
        service.refresh()

        then:
        service.getInt("gate.rate_limit_per_minute", 6) == 3
    }

    private SystemParamDO param(String key, String value) {
        def paramDO = new SystemParamDO()
        paramDO.setParamKey(key)
        paramDO.setParamValue(value)
        return paramDO
    }
}
