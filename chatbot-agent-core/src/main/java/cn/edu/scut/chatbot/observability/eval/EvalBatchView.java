package cn.edu.scut.chatbot.observability.eval;

import java.time.LocalDateTime;

/**
 * 评估批次对外视图（management 收口：管理面只见视图不见 DO——宪法第五条）。
 * scope / report / costStat 是管理面直接渲染的 JSON 原文。
 */
public record EvalBatchView(long id,
                            String batchKind,
                            String scope,
                            String status,
                            String report,
                            String costStat,
                            LocalDateTime createdAt,
                            LocalDateTime finishedAt) {

    public static EvalBatchView from(EvalBatchDO batch) {
        return new EvalBatchView(batch.getId(), batch.getBatchKind(), batch.getScope(),
                batch.getStatus(), batch.getReport(), batch.getCostStat(),
                batch.getCreatedAt(), batch.getFinishedAt());
    }
}
