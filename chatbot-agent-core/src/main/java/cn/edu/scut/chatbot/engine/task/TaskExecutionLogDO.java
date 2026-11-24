package cn.edu.scut.chatbot.engine.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * task_execution_log 表 DO：执行留痕（round 轮次 / progress 进度——进度只写执行记录不触发推理）。
 */
@Data
public class TaskExecutionLogDO {

    private Long id;

    private Long taskId;

    /**
     * round / progress
     */
    private String recordKind;

    private String note;

    private LocalDateTime createdAt;

    public static final String KIND_ROUND = "round";
    public static final String KIND_PROGRESS = "progress";
}
