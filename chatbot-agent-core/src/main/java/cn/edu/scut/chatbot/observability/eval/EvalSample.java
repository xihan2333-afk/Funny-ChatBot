package cn.edu.scut.chatbot.observability.eval;

/**
 * 评估样本引用：线上 Turn（caseId 空）或评测集题目（turnId 空）。inputDigest /
 * outputDigest 是触发消息簇与 Agent 实际发言的摘要——判分输入的自含材料，
 * 不依赖 ES 保留期。
 */
public record EvalSample(Long caseId,
                         String sourceKind,
                         String platform,
                         String botId,
                         String groupId,
                         Long turnId,
                         String traceId,
                         String inputDigest,
                         String outputDigest) {
}
