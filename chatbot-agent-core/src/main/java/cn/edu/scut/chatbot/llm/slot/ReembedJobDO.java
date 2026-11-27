package cn.edu.scut.chatbot.llm.slot;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 重嵌入迁移任务 DO（对应 reembed_job 表）
 */
@Data
public class ReembedJobDO {

    private Long id;
    private Long fromModelId;
    private Long toModelId;
    private Integer fromDimension;
    private Integer toDimension;
    private String status;
    private Integer totalCount;
    private Integer processedCount;
    private Integer failedCount;
    private String errorMessage;
    private String operator;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
