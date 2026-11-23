package cn.edu.scut.chatbot.engine.outbox;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * outbox 表 DO：出站事件（data-model 第 11 节）。先落库取得 outboxId 再投递出流；
 * 投递回执按 id 关联回填；崩溃恢复凭本表查明在途发言、杜绝对外重复。
 */
@Data
public class OutboxDO {

    /**
     * 雪花 ID（outboxId，先发号再落库）
     */
    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * 出原语类型短码（send_group_message / set_reaction / ...）
     */
    private String type;

    /**
     * 出事件载荷 JSON
     */
    private String payload;

    private Long turnId;

    private Long taskId;

    /**
     * W3C traceparent
     */
    private String traceContext;

    /**
     * pending / settled / failed
     */
    private String status;

    /**
     * 回执内容 JSON（平台消息 ID、失败原因分类）
     */
    private String report;

    private String errorCode;

    private LocalDateTime settledAt;

    private LocalDateTime createdAt;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_DISPATCHING = "dispatching";
    public static final String STATUS_SETTLED = "settled";
    public static final String STATUS_FAILED = "failed";
}
