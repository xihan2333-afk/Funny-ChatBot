package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * group 表 DO：群实体与生命周期（data-model 第 12 节）。
 * in_service=0 或 lifecycle=DISCOVERED 的群：消息不登记触发器不留档（research R2——
 * 运营已做出明确决策，系统无须再评估）；BotEvent 例外走生命周期联动。
 */
@Data
public class GroupDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    private String groupName;

    /**
     * DISCOVERED 已发现未服务 / ACTIVE / PAUSED / DESTROYED
     */
    private String lifecycle;

    /**
     * 运营开关：0 一切消息直接忽略不回应
     */
    private Integer inService;

    /**
     * 最近一次暂停起点（恢复时算顺延）
     */
    private LocalDateTime pausedAt;

    /**
     * 累计暂停时长毫秒（一切计时顺延的依据）
     */
    private Long pausedAccumulatedMillis;

    private LocalDateTime discoveredAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ===== 生命周期短码 =====
    public static final String LIFECYCLE_DISCOVERED = "DISCOVERED";
    public static final String LIFECYCLE_ACTIVE = "ACTIVE";
    public static final String LIFECYCLE_PAUSED = "PAUSED";
    public static final String LIFECYCLE_DESTROYED = "DESTROYED";
}
