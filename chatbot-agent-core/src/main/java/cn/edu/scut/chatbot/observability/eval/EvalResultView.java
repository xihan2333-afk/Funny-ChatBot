package cn.edu.scut.chatbot.observability.eval;

import java.time.LocalDateTime;

/**
 * 评估结果对外视图（management 收口）。score 映射为结构化 {@link EvalScore}。
 */
public record EvalResultView(long id,
                             long batchId,
                             Long caseId,
                             String sourceKind,
                             String platform,
                             String botId,
                             String groupId,
                             Long turnId,
                             String traceId,
                             EvalScore score,
                             String decision,
                             String explanation,
                             String attribution,
                             String judgeModel,
                             LocalDateTime createdAt) {

    static EvalResultView from(EvalResultDO result) {
        return new EvalResultView(result.getId(), result.getBatchId(), result.getCaseId(),
                result.getSourceKind(), result.getPlatform(), result.getBotId(), result.getGroupId(),
                result.getTurnId(), result.getTraceId(),
                result.getScore() == null ? null : EvalScore.parse(result.getScore()),
                result.getDecision(), result.getExplanation(), result.getAttribution(),
                result.getJudgeModel(), result.getCreatedAt());
    }
}
