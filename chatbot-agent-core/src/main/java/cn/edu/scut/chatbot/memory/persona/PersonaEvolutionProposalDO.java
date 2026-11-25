package cn.edu.scut.chatbot.memory.persona;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * persona_evolution_proposal 表 DO（data-model §1.6）：数据辅助 + 人工确认的
 * 人格演进提案。JSON 列按全库惯例映射 String、服务层经 JsonMappers 解析。
 */
@Data
public class PersonaEvolutionProposalDO {

    /**
     * 雪花 ID（IdGenerator 发放先于落库）
     */
    private Long id;

    private Long personaId;

    /**
     * 统计周期起（默认月度）
     */
    private LocalDateTime periodStart;

    /**
     * 统计周期止
     */
    private LocalDateTime periodEnd;

    /**
     * 五信号源关键指标快照 JSON（留档 = 可追溯「当时看到了什么数据」）
     */
    private String signalsDigest;

    /**
     * 提案条目数组 JSON：{fieldPath, suggestedValue, currentValue, evidence,
     * expectedEffect, itemStatus}
     */
    private String proposalItems;

    /**
     * pending_review / accepted / rejected
     */
    private String status;

    private String reviewer;

    private String remark;

    private LocalDateTime createdAt;

    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_REJECTED = "rejected";
}
