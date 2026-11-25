package cn.edu.scut.chatbot.llm.slot;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * llm_call_log 表 DO：调用计量事实源（CostRecorder 异步批量落账，US8）。
 */
@Data
public class LlmCallLogDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    private String runKind;

    private Long runId;

    private String slotCode;

    private String providerCode;

    private String modelId;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer cachedTokens;

    private BigDecimal costAmount;

    private Integer ttftMs;

    private Integer totalMs;

    private String stopReason;

    private String errorCode;

    private String traceId;

    private Boolean replay;

    private LocalDateTime createdAt;
}
