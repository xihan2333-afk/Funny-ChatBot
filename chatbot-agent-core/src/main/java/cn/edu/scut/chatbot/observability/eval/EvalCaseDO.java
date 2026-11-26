package cn.edu.scut.chatbot.observability.eval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * eval_case 表 DO（V8 已建）：评测集三层资产同库（seed 种子 / golden 黄金 /
 * fault 错题）。scenario 与 rubric 是 JSON 列映射 String；判分侧用
 * {@link RubricItem#parseRubric} 反序列化 rubric。
 */
@Data
public class EvalCaseDO {

    private Long id;

    /**
     * 隔离三元组：线上挖出的黄金 / 错题按群管理可查；种子题为构造场景可空
     */
    private String platform;

    private String botId;

    private String groupId;

    /**
     * seed 种子 / golden 黄金 / fault 错题
     */
    private String caseType;

    /**
     * 场景卡五节点 JSON：groupBackground / memberPersonas / preloadedState /
     * priorScript / triggerEvent（结构校验见 {@link ScenarioCard}）
     */
    private String scenario;

    /**
     * 题目级二元检查项数组 JSON（每项结构见 {@link RubricItem}）
     */
    private String rubric;

    /**
     * 修订留痕（归因 rubricError 修订后递增）
     */
    private Integer rubricVersion;

    /**
     * generated 小模型生成人工修订 / manual 人工挑选 / marked 线上问题标记挖掘
     */
    private String origin;

    /**
     * 来源链路 id（线上挖出的题可回看轨迹；种子题可空）
     */
    private String traceRef;

    /**
     * 触发消息簇摘要（自含，不依赖 ES 保留期）
     */
    private String inputDigest;

    /**
     * 当时 Agent 实际发言摘要
     */
    private String outputDigest;

    /**
     * ready 已入库 / draft 生成待修订 / discarded 已废弃
     */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ===== 字典值常量 =====
    public static final String TYPE_SEED = "seed";
    public static final String TYPE_GOLDEN = "golden";
    public static final String TYPE_FAULT = "fault";
    public static final String ORIGIN_GENERATED = "generated";
    public static final String ORIGIN_MANUAL = "manual";
    public static final String ORIGIN_MARKED = "marked";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_DISCARDED = "discarded";
}
