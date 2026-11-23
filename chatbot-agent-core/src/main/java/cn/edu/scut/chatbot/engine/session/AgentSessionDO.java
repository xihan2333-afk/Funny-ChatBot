package cn.edu.scut.chatbot.engine.session;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * agent_session 表 DO：常驻会话元数据（data-model 第 9 节）——每群每 Bot 一行，
 * 惰性创建（群首次交互）、持久化恢复。
 */
@Data
public class AgentSessionDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    private Long personaId;

    /**
     * ACTIVE / PAUSED / DESTROYED
     */
    private String lifecycle;

    /**
     * active / quiet 水位档（压缩任务调整）
     */
    private String windowLevel;

    /**
     * label/intensity/cause/updatedAt JSON（第一版直写 + 幅度钳制）
     */
    private String emotion;

    /**
     * 最新压缩摘要指针——摘要区只投这一条
     */
    private Long summaryEntryId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String LIFECYCLE_ACTIVE = "ACTIVE";
    public static final String LIFECYCLE_PAUSED = "PAUSED";
    public static final String LIFECYCLE_DESTROYED = "DESTROYED";
    public static final String WINDOW_ACTIVE = "active";
    public static final String WINDOW_QUIET = "quiet";
}
