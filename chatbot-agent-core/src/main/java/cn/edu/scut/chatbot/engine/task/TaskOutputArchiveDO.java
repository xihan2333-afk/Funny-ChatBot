package cn.edu.scut.chatbot.engine.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * task_output_archive 表 DO：大结果分片存档（chunk 32KB，readFullOutput 按片取回）。
 */
@Data
public class TaskOutputArchiveDO {

    private Long id;

    private Long taskId;

    private Integer chunkIndex;

    private String chunkText;

    private LocalDateTime createdAt;
}
