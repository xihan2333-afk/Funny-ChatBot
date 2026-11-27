package cn.edu.scut.chatbot.observability.flywheel;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * improvement_proposal 表 DO（V8 已建，R12 增补表）：经验挖掘产出的改进提案，
 * 跨日人工审阅的状态实体。id 是应用侧雪花（进程外可见 ID——管理面引用与发布关联），
 * 先于落库发放。problem_card 是 JSON 列映射 String。
 */
@Data
public class ImprovementProposalDO {

    /**
     * 雪花 ID（应用侧发放先于落库）
     */
    private Long id;

    /**
     * mining 经验挖掘 / manual 管理员直接发起
     */
    private String sourceKind;

    /**
     * 问题卡 JSON：{description, evidence[] 证据引用（traceId / eval_result id /
     * Span 摘要）, suggestTarget 建议落点, expectedEffect 预期效果}
     */
    private String problemCard;

    /**
     * promptFragment / personaParam / skill / l1Prompt / toolConfig 五类落点
     */
    private String target;

    /**
     * pending 待归因 / attributed 已归因待审阅 / accepted 已接受进入编辑 /
     * rejected 已拒绝 / landed 已落地发布
     */
    private String status;

    /**
     * agent / caseMiss / rubricError（只有 agent 进入 accepted）
     */
    private String attribution;

    /**
     * 同证据重复提案去重键（uk_dedup；NULL 不参与唯一约束）
     */
    private String dedupKey;

    /**
     * 审阅 / 拒绝理由
     */
    private String reviewNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ===== 字典值常量（状态机：pending → attributed → accepted → landed | rejected）=====
    public static final String SOURCE_MINING = "mining";
    public static final String SOURCE_MANUAL = "manual";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ATTRIBUTED = "attributed";
    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_LANDED = "landed";
    public static final String ATTRIBUTION_AGENT = "agent";
    public static final String ATTRIBUTION_CASE_MISS = "caseMiss";
    public static final String ATTRIBUTION_RUBRIC_ERROR = "rubricError";
}
