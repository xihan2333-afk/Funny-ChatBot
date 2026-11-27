package cn.edu.scut.chatbot.observability.eval;

/**
 * 评估器接口（eval-and-flywheel 契约第 1 节）——两类实现同一判分语义：
 * {@link CodeRuleEvaluator} 纯代码断言（type=code 检查项）、{@link AgentJudgeEvaluator}
 * eval_judge 槽位小模型按 Rubric 逐项判分（type=judge 检查项）。两者的输出都经
 * {@link RubricJudge} 的同一套负区间与加权口径合成。
 */
public interface Evaluator {

    EvalOutcome evaluate(EvalInput input);
}
