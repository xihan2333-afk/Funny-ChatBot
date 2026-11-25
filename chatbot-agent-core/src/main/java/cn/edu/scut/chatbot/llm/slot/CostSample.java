package cn.edu.scut.chatbot.llm.slot;

import cn.edu.scut.chatbot.loop.message.AssistantMessage;

/**
 * 单次调用计量（data-model 第 11 节、research R8）：用量、TTFT、总延迟、结局——
 * 与 {@link cn.edu.scut.chatbot.llm.gateway.LlmCallMeta} 一起构成 llm_call_log 的
 * 完整输入。观测元数据不进 loop 契约（内核不被观测需求污染）。
 *
 * @param usage      用量（可空：上游未返回用量块——落账列 NULL 不阻塞）
 * @param ttftMs     请求发出到首个增量事件（Thinking/Text/ToolCall Delta）
 * @param totalMs    请求发出到终止事件
 * @param stopReason stop / toolUse / error / aborted
 * @param errorCode  Failed 时携带（llm.* 点分码；成功为 null）
 */
public record CostSample(
        AssistantMessage.TokenUsage usage,
        long ttftMs,
        long totalMs,
        String stopReason,
        String errorCode
) {
}
