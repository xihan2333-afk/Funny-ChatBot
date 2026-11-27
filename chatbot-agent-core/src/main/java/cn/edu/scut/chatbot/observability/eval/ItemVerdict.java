package cn.edu.scut.chatbot.observability.eval;

/**
 * 单个检查项的二元判定结果：passed 通过与否 + evidence 证据引用（可空——
 * 分数必须可解释，judge 项的证据进 explanation）。
 */
public record ItemVerdict(int index, boolean passed, String evidence) {
}
