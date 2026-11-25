// 守护纪律：L1 经 eval_l1 槽位产出 Decision（低温度 + structuredOutput）、
// 输出非法 JSON 或调用失败按接口契约上抛（保守 ignore 归 L1BatchAssessor 不在此重复）
// （宪法第七条；contracts/capability-api.md 第 2/3 节；spec US7 场景 6）
package cn.edu.scut.chatbot.engine.gate

import cn.edu.scut.chatbot.llm.gateway.CallOnceResult
import cn.edu.scut.chatbot.llm.gateway.ModelGateway
import cn.edu.scut.chatbot.loop.GenerationParams
import spock.lang.Specification

class GatewayL1AssessorTest extends Specification {

    def gateway = Mock(ModelGateway)
    def assessor = new GatewayL1Assessor(gateway)

    def digests = [
            new L1AssessmentModel.TriggerDigest("dedup-1", "at", "有人@机器人"),
            new L1AssessmentModel.TriggerDigest("dedup-2", "keyword", "提到广州天气"),
    ]

    def "合法 JSON 产出 AssessedDecision 列表（eval_l1 槽位 + 强制 JSON 参数）"() {
        given: "stub 携参数约束：低温度 + structuredOutput 强制 JSON（不满足则无匹配返回 null → 测试失败）"
        gateway.callOnce("eval_l1", _, _, _,
                { GenerationParams p -> p.structuredOutput() && p.temperature() < 0.3 }) >> CallOnceResult.success(
                '''[{"dedupKey":"dedup-1","decision":"accept","reason":"直接@应答"},
                    {"dedupKey":"dedup-2","decision":"ignore","reason":"无明确指向"}]''',
                null)

        when:
        def decisions = assessor.assess("评估提示词", digests, ["A：在吗"], "好奇心强", null)

        then:
        decisions.size() == 2
        decisions[0].dedupKey() == "dedup-1"
        decisions[0].decision() == "accept"
        decisions[1].decision() == "ignore"
    }

    def "非法 JSON 输出上抛（解析容错归 L1BatchAssessor 保守 ignore）"() {
        given:
        gateway.callOnce("eval_l1", _, _, _, _) >> CallOnceResult.success("这不是 JSON", null)

        when:
        assessor.assess("评估提示词", digests, [], null, null)

        then:
        thrown(Exception)
    }

    def "调用失败（槽位未绑定等）上抛由既有重试与降级承担"() {
        given:
        gateway.callOnce("eval_l1", _, _, _, _) >> CallOnceResult.failure("llm.config_missing", "槽位未绑定")

        when:
        assessor.assess("评估提示词", digests, [], null, null)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("llm.config_missing")
    }
}
