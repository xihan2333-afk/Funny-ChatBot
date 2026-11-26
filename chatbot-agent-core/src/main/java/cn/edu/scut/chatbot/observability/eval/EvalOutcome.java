package cn.edu.scut.chatbot.observability.eval;

import java.util.List;

/**
 * 评估器统一输出（eval-and-flywheel 契约第 1 节）：五组分数 + 判定 + 解释 +
 * 判分模型。failureReason 非空表示评估本身失败（槽位不可用 / 调用失败 / 输出
 * 非法 / explanation 缺失）——该条目进批次 report 的 failed 列表，不落 eval_result。
 */
public record EvalOutcome(EvalScore score,
                          String decision,
                          String explanation,
                          String judgeModel,
                          String failureReason,
                          List<ItemVerdict> itemVerdicts) {

    public boolean failed() {
        return failureReason != null;
    }

    public static EvalOutcome failure(String reason) {
        return new EvalOutcome(null, null, null, null, reason, List.of());
    }
}
