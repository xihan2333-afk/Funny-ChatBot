package cn.edu.scut.chatbot.observability.eval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * eval_batch 表 DO（V8 已建）：评估执行批次——每日抽样 / 手动圈选 / 评测集回测 /
 * 安全对抗四类动作共用一张批次表。状态机 running → done | failed；成本与报表随批次
 * 留档（JSON 列映射 String，由 {@link EvalTaskServiceImpl} 序列化）。
 */
@Data
public class EvalBatchDO {

    private Long id;

    /**
     * daily / manual / replay / safety
     */
    private String batchKind;

    /**
     * 圈选条件 JSON：时间范围 / 群 / 样本数 / 评测集范围
     */
    private String scope;

    /**
     * running / done / failed
     */
    private String status;

    /**
     * 汇总报表 JSON：各维度通过率、版本对比、失败清单
     */
    private String report;

    /**
     * 本批模型成本 JSON（replay 批次单独合计，与线上隔离）
     */
    private String costStat;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    // ===== 字典值常量 =====
    public static final String KIND_DAILY = "daily";
    public static final String KIND_MANUAL = "manual";
    public static final String KIND_REPLAY = "replay";
    public static final String KIND_SAFETY = "safety";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
}
