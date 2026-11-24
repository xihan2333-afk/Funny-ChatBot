package cn.edu.scut.chatbot.engine.session;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * transcript_entry 表 DO：转录条目（只增不改；存储与可见性分离——表是全量、窗口是投影）。
 */
@Data
public class TranscriptEntryDO {

    /**
     * 雪花 ID（messageId）
     */
    private Long id;

    private Long sessionId;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * AgentMessageKind 短码
     */
    private String kind;

    /**
     * 类型化载荷 JSON（TranscriptCodec 编解码）
     */
    private String payload;

    private Integer weight;

    private LocalDateTime occurredAt;

    private Long runId;
}
