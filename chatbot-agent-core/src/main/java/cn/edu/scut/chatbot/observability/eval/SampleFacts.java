package cn.edu.scut.chatbot.observability.eval;

/**
 * 代码判据事实（CodeRuleEvaluator 的输入侧）：评估侧从 Turn 表 / 转录组装的可编程
 * 断言事实——发言条数、端到端时延毫秒、出事件是否携带 outboxId。字段可空表示
 * 该事实不可得（对应断言按失败保守处理）。
 */
public record SampleFacts(Integer speakCount, Long latencyMs, Boolean outboxCarried) {
}
