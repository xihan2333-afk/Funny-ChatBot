package cn.edu.scut.chatbot.observability.flywheel;

import java.time.LocalDateTime;

/**
 * 改进提案对外视图（management 收口）。
 */
public record ProposalView(long id,
                           String sourceKind,
                           String problemCard,
                           String target,
                           String status,
                           String attribution,
                           String reviewNote,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {

    static ProposalView from(ImprovementProposalDO row) {
        return new ProposalView(row.getId(), row.getSourceKind(), row.getProblemCard(),
                row.getTarget(), row.getStatus(), row.getAttribution(), row.getReviewNote(),
                row.getCreatedAt(), row.getUpdatedAt());
    }
}
