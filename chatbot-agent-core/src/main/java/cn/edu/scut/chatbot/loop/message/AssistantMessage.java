package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;
import java.util.List;

/**
 * 基准条目：模型产出（data-model 第 2 节）。带错误标记的残缺消息（stopReason=error/aborted）
 * 也以本条目落转录——保留给模型看，但永不参与发言投递与兜底（残缺纪律）。
 */
public record AssistantMessage(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String textContent,
        List<ToolCallSegment> toolCalls,
        String stopReason,
        TokenUsage usage
) implements AgentMessage {

    /**
     * 自然结束（模型消息不再含工具调用）
     */
    public static final String STOP_REASON_STOP = "stop";
    /**
     * 以工具调用结束（下轮继续）
     */
    public static final String STOP_REASON_TOOL_USE = "toolUse";
    /**
     * 模型侧错误（流 Failed 终止；带此标记的条目永不投递）
     */
    public static final String STOP_REASON_ERROR = "error";
    /**
     * 取消终止（卡死检测/任务取消；带此标记的条目永不投递）
     */
    public static final String STOP_REASON_ABORTED = "aborted";

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.ASSISTANT;
    }

    /**
     * 残缺纪律判定：error/aborted 标记的条目内容保留转录但不投递、不参与兜底
     */
    public boolean isDefective() {
        return STOP_REASON_ERROR.equals(stopReason) || STOP_REASON_ABORTED.equals(stopReason);
    }

    /**
     * 工具调用段（callId 与后续 ToolResultMessage 配对——投影切点纪律依赖此配对）
     */
    public record ToolCallSegment(String callId, String name, String argumentsJson) {
    }

    /**
     * token 用量五值（成本统计的事件数据源）。cachedTokens 是缓存命中量（各家字段名
     * 接收侧探测归一），为 input 的子集——成本折算命中部分按缓存单价、不重复计输入价。
     */
    public record TokenUsage(int inputTokens, int outputTokens, int reasoningTokens,
                             int cachedTokens, int totalTokens) {

        public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0);
    }
}
