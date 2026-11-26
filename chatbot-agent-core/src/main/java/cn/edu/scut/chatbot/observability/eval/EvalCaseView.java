package cn.edu.scut.chatbot.observability.eval;

import java.time.LocalDateTime;

/**
 * 评测集条目对外视图（management 收口）。
 */
public record EvalCaseView(long id,
                           String platform,
                           String botId,
                           String groupId,
                           String caseType,
                           String scenario,
                           String rubric,
                           int rubricVersion,
                           String origin,
                           String traceRef,
                           String inputDigest,
                           String outputDigest,
                           String status,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {

    static EvalCaseView from(EvalCaseDO row) {
        return new EvalCaseView(row.getId(), row.getPlatform(), row.getBotId(), row.getGroupId(),
                row.getCaseType(), row.getScenario(), row.getRubric(),
                row.getRubricVersion() == null ? 1 : row.getRubricVersion(),
                row.getOrigin(), row.getTraceRef(), row.getInputDigest(), row.getOutputDigest(),
                row.getStatus(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
