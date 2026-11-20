package cn.edu.scut.chatbot.access.archive;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * raw_event_log 表的数据对象（设计方案 3.3.1 DDL，接入域唯一表）。
 *
 * <p>DO 仅用于 MyBatis 映射的样板缩减（Lombok 只出现在 DO 上，对外契约一律 record）。
 * 表结构由 agent-core 的 Flyway 迁移统一管理，接入层只写不建。
 */
@Data
public class RawEventLogDO {

    /**
     * 自增主键；INSERT 后经 useGeneratedKeys 回填，组装 RawEventRef 进信封
     */
    private Long id;

    /**
     * 平台标识（同信封 platform）
     */
    private String platform;

    /**
     * 机器人账号（同信封 botId）
     */
    private String botId;

    /**
     * in 入报文 / out 出报文
     */
    private String direction;

    /**
     * 平台事件类型，解析可得时填（如 message.group）
     */
    private String eventType;

    /**
     * 平台原生事件 ID / message_id（出方向填平台回执的 message_id）
     */
    private String dedupKey;

    /**
     * 平台原始报文 JSON 全文，不做任何加工——「信息不丢失」的最终落点
     */
    private String rawPayload;

    /**
     * 平台侧事件时间
     */
    private LocalDateTime occurredAt;

    /**
     * 接入层接收时间
     */
    private LocalDateTime receivedAt;

    /**
     * 落库时间（DB 默认值兜底，插入语句不写该列）
     */
    private LocalDateTime createdAt;
}
