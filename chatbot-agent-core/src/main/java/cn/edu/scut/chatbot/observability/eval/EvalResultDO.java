package cn.edu.scut.chatbot.observability.eval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * eval_result 表 DO（V8 已建）：评估结果——五组分数 + 判定 + 归因 + 判分模型留痕。
 * score JSON 的结构与 {@link EvalScore} 一致；盲评人工打分行 judge_model 为 null
 * （人机一致率的对照方）。
 */
@Data
public class EvalResultDO {

    private Long id;

    private Long batchId;

    /**
     * 线上抽样评估无对应题目时为空
     */
    private Long caseId;

    /**
     * daily / manual / blind 随机盲评 / replay / safety
     */
    private String sourceKind;

    private String platform;

    private String botId;

    private String groupId;

    private Long turnId;

    private String traceId;

    /**
     * 分数 JSON：{total, respond, process, form, cost, safety}——红线失败时 total 无效
     */
    private String score;

    /**
     * pass / badcase
     */
    private String decision;

    /**
     * 判分解释与证据引用（分数必须可解释）
     */
    private String explanation;

    /**
     * agent / caseMiss / rubricError（人工归因）
     */
    private String attribution;

    /**
     * 打分模型（盲评人工打分时为 null）
     */
    private String judgeModel;

    private LocalDateTime createdAt;

    // ===== 字典值常量 =====
    public static final String SOURCE_DAILY = "daily";
    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_BLIND = "blind";
    public static final String SOURCE_REPLAY = "replay";
    public static final String SOURCE_SAFETY = "safety";
    public static final String DECISION_PASS = "pass";
    public static final String DECISION_BADCASE = "badcase";
    public static final String ATTRIBUTION_AGENT = "agent";
    public static final String ATTRIBUTION_CASE_MISS = "caseMiss";
    public static final String ATTRIBUTION_RUBRIC_ERROR = "rubricError";
}
