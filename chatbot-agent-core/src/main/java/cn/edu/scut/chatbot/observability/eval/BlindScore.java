package cn.edu.scut.chatbot.observability.eval;

/**
 * 盲评人工打分（submitBlindScore 入参）：五组分数 + 判定 + 解释。落库为
 * source_kind=blind 且 judge_model=null 的对照行——人机一致率的对照方。
 */
public record BlindScore(EvalScore score, String decision, String explanation) {
}
