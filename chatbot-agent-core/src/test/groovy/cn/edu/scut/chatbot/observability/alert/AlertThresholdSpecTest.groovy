// 守护纪律：十二条内置规则共用 AlertThresholdSpec 判定语义——比较方式边界、
// 缺省阈值与非法 JSON 拒绝不随重构漂移（T033）
package cn.edu.scut.chatbot.observability.alert

import cn.edu.scut.chatbot.bootstrap.JsonMappers
import spock.lang.Specification

class AlertThresholdSpecTest extends Specification {

    def "gt 边界：等于不命中 大于命中"(double sample, boolean expected) {
        expect:
        new AlertThresholdSpec("gt", 0.10, null, 30).matches(sample) == expected

        where:
        sample || expected
        0.10   || false
        0.099  || false
        0.11   || true
        0.15   || true
    }

    def "gte 边界：等于命中 略小不命中"() {
        expect:
        new AlertThresholdSpec("gte", 10, null, 30).matches(10)
        !new AlertThresholdSpec("gte", 10, null, 30).matches(9.999)
    }

    def "lt 边界：等于不命中 小于命中"() {
        expect:
        new AlertThresholdSpec("lt", 0.5, null, 30).matches(0.5) == false
        new AlertThresholdSpec("lt", 0.5, null, 30).matches(0.499) == true
    }

    def "lte 边界：等于命中"() {
        expect:
        new AlertThresholdSpec("lte", 3, null, 30).matches(3)
    }

    def "eq 边界：相等命中 不等不命中"() {
        expect:
        new AlertThresholdSpec("eq", 0, null, 30).matches(0)
        !new AlertThresholdSpec("eq", 0, null, 30).matches(1)
    }

    def "value 缺省按 0 处理（gt 0 即有值告警语义）"() {
        expect:
        new AlertThresholdSpec("gt", null, null, 30).matches(1)
        !new AlertThresholdSpec("gt", null, null, 30).matches(0)
    }

    def "未知 op 不命中（宁缺勿滥）"() {
        expect:
        !new AlertThresholdSpec("between", 1, null, 30).matches(1)
        !new AlertThresholdSpec(null, 1, null, 30).matches(1)
    }

    def "V8 种子 JSON 逐条可解析且判定语义正确"() {
        given:
        def mapper = JsonMappers.shared()

        when:
        def spec = AlertThresholdSpec.parse(mapper, '''
        { "op": "gt", "value": 0.10, "suppressMin": 30 }''')

        then:
        spec.op() == "gt"
        spec.value() == 0.10
        spec.paramKey() == null
        spec.suppressMin() == 30
        !spec.matches(0.10)
        spec.matches(0.11)
    }

    def "paramKey 形态（日成本超限）解析保留键名"() {
        given:
        def spec = AlertThresholdSpec.parse(JsonMappers.shared(),
                '{ "op": "gt", "paramKey": "security.breaker.globalDailyLimit", "suppressMin": 120 }')

        expect:
        spec.value() == null
        spec.paramKey() == "security.breaker.globalDailyLimit"
        spec.suppressMin() == 120
    }

    def "非法 JSON 拒绝并带原文"() {
        when:
        AlertThresholdSpec.parse(JsonMappers.shared(), "{not-json")

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("threshold")
    }
}
