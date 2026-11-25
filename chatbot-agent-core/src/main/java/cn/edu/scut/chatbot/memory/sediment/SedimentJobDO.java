package cn.edu.scut.chatbot.memory.sediment;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * sediment_job 表 DO（data-model §1.2）：沉淀工单——工单不是日志，
 * pending → processing → done/failed 是持久队列（CAS 认领 + 超时重投 + 两级幂等）。
 *
 * <p>JSON 列（payload / output_ref）沿用全库惯例映射 String、服务层 JsonMappers 解析。
 */
@Data
public class SedimentJobDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * compress（压缩事务落单，常态主通道）/ daily_scan（兜底补投）
     */
    private String triggerKind;

    /**
     * compress 取 summaryEntryId（每次压缩唯一）；daily_scan 取扫描批次标识
     */
    private String idempotentKey;

    /**
     * 转录区间引用（首尾 entryId）+ 最新摘要 entryId JSON
     */
    private String payload;

    /**
     * pending / processing / done / failed
     */
    private String status;

    /**
     * 超限告警（failed 终态不再自动重投，管理面可手动重投）
     */
    private Integer retryCount;

    /**
     * done 回填：新建/合并记忆条目 id 列表 + 画像变更摘要 JSON
     */
    private String outputRef;

    private LocalDateTime createdAt;

    /**
     * 兼作认领心跳（processing 超时判定基准——ON UPDATE 自动刷新）
     */
    private LocalDateTime updatedAt;

    public static final String KIND_COMPRESS = "compress";
    public static final String KIND_DAILY_SCAN = "daily_scan";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
}
