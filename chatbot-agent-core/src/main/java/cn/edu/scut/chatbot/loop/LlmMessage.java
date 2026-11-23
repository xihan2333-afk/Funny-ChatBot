package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.loop.message.AssistantMessage;

import java.util.List;

/**
 * 统一消息投影（convertToLlm 钩子的产出、ModelRequest 的消息元素）：
 * 方言无关的扁平形态，llm 翻译器再转 openai/gemini 方言。
 *
 * <p>role 取值 user / assistant / tool：user 与 assistant 携带 text 与 toolCalls
 * （assistant 的工具调用段）；tool 携带 toolCallId 与结果文本（isError 时文本即错误说明）。
 */
public record LlmMessage(
        String role,
        String text,
        List<AssistantMessage.ToolCallSegment> toolCalls,
        String toolCallId,
        boolean isError
) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static LlmMessage user(String text) {
        return new LlmMessage(ROLE_USER, text, List.of(), null, false);
    }

    public static LlmMessage assistant(String text, List<AssistantMessage.ToolCallSegment> toolCalls) {
        return new LlmMessage(ROLE_ASSISTANT, text, toolCalls, null, false);
    }

    public static LlmMessage toolResult(String toolCallId, String text, boolean isError) {
        return new LlmMessage(ROLE_TOOL, text, List.of(), toolCallId, isError);
    }
}
