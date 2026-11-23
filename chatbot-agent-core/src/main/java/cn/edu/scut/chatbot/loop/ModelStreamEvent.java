package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.loop.message.AssistantMessage;

/**
 * 模型流事件密封接口，十一种（data-model 第 4 节、契约 loop-kernel.md 第 1 节）：
 * 文本/思考/工具调用三段式（Start/Delta/End）九种 + Completed/Failed 两种终止。
 *
 * <p>思考增量只作「流还活着」的活跃信号参与卡死检测，内容不进转录；
 * 终止判定只看 Completed 携带的 AssistantMessage；Failed 之前已推的增量
 * 保留在调用方手里（半截内容 + 错误标记）。
 */
public sealed interface ModelStreamEvent permits
        ModelStreamEvent.TextStart,
        ModelStreamEvent.TextDelta,
        ModelStreamEvent.TextEnd,
        ModelStreamEvent.ThinkingStart,
        ModelStreamEvent.ThinkingDelta,
        ModelStreamEvent.ThinkingEnd,
        ModelStreamEvent.ToolCallStart,
        ModelStreamEvent.ToolCallDelta,
        ModelStreamEvent.ToolCallEnd,
        ModelStreamEvent.Completed,
        ModelStreamEvent.Failed {

    record TextStart() implements ModelStreamEvent {
    }

    record TextDelta(String text) implements ModelStreamEvent {
    }

    record TextEnd() implements ModelStreamEvent {
    }

    record ThinkingStart() implements ModelStreamEvent {
    }

    record ThinkingDelta(String text) implements ModelStreamEvent {
    }

    record ThinkingEnd() implements ModelStreamEvent {
    }

    /**
     * @param index  工具调用在批次内的序号（并行多工具交错分片的归并键）
     * @param callId 调用 ID（与 ToolResultMessage 配对键）
     * @param name   工具名
     */
    record ToolCallStart(int index, String callId, String name) implements ModelStreamEvent {
    }

    /**
     * @param index             归并键
     * @param argumentsFragment 参数 JSON 增量分片（拼接后才是完整 JSON——截断即标记失败不执行）
     */
    record ToolCallDelta(int index, String argumentsFragment) implements ModelStreamEvent {
    }

    record ToolCallEnd(int index) implements ModelStreamEvent {
    }

    /**
     * 正常终止：携带完整 AssistantMessage（文本段、工具调用段、stopReason、用量）
     */
    record Completed(AssistantMessage message) implements ModelStreamEvent {
    }

    /**
     * 异常终止（网络/超时/限流/截断/取消）：llm.* 错误码——绝不以异常抛出（内核纪律）
     */
    record Failed(String errorCode, String errorMessage) implements ModelStreamEvent {
    }
}
