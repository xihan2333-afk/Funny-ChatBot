package cn.edu.scut.chatbot.engine.gate;

import java.util.List;

/**
 * L1 注意力评估的模型调用抽象（research R1 决策：独立抽象不走 StreamFn——
 * L1 是无状态单次结构化输出调用，没有轮次、没有工具、没有转录，与 AgentLoop 执行形态
 * 完全不同；强行共用会让循环契约被评估场景污染）。
 *
 * <p>2.3 feature 落地时由模型网关实现（eval_l1 槽位 + 结构化输出解析）；
 * 本 feature 期间由测试域 ScriptedL1Assessor 剧本假件驱动。
 */
public interface L1AssessmentModel {

    /**
     * 批量评估：同群积压的全部待评估触发器合并一次判断。
     *
     * @param assessmentPrompt  评估提示词（standalone 层模板渲染结果）
     * @param batchDigests      本批触发器摘要列表（含 dedupKey）
     * @param recentMessages    近期群消息投影（有界上下文三路之一；发言人态度显著者
     *                          行首带档位标注如「[挚友]张三：...」——无感不标注）
     * @param personaPreference 人格注意力偏好摘要（健谈度/话题敏感/主动性/插话倾向）
     * @param groupStateDigest  群状态摘要（活跃度/当前话题/近期氛围/Agent 发言频率）
     * @return 每条触发器一项的结构化决策（合法值 accept / ignore）
     * @throws Exception 调用失败（指数退避重试由 L1BatchAssessor 负责，本接口不吞异常）
     */
    List<AssessedDecision> assess(String assessmentPrompt,
                                  List<TriggerDigest> batchDigests,
                                  List<String> recentMessages,
                                  String personaPreference,
                                  String groupStateDigest) throws Exception;

    /**
     * 本批触发器摘要（输入面）
     */
    record TriggerDigest(String dedupKey, String eventType, String digest) {
    }

    /**
     * 单条结构化决策（输出面）
     */
    record AssessedDecision(String dedupKey, String decision, String reason) {
    }
}
