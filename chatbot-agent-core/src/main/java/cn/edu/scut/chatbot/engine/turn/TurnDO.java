package cn.edu.scut.chatbot.engine.turn;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * turn 表 DO：一次被动回应的状态机（data-model 第 7 节）。
 * 单飞不变式：每群同一时刻至多一个 GENERATING/SETTLING（内存闸 + DB 条件 UPDATE 双保险）。
 */
@Data
public class TurnDO {

    /**
     * 雪花 ID（turnId）
     */
    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * TurnStatus 短码
     */
    private String status;

    /**
     * 本 Turn 消化的触发器 ID 列表 JSON（含同批合并）
     */
    private String triggerIds;

    /**
     * 产生的出事件 ID 列表 JSON，回执按列表逐条结算
     */
    private String outboxIds;

    /**
     * SILENT/DROPPED 原因
     */
    private String silentReason;

    /**
     * FAILED 时的点分错误码
     */
    private String errorCode;

    private LocalDateTime acceptedAt;

    private LocalDateTime finishedAt;
}
