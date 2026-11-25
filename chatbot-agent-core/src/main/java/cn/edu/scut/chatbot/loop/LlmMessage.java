package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.ImageContent;

import java.util.List;

/**
 * 统一消息投影（convertToLlm 钩子的产出、ModelRequest 的消息元素）：
 * 方言无关的扁平形态，llm 翻译器再转 openai/gemini 方言。
 *
 * <p>role 取值 user / assistant / tool：user 与 assistant 携带 text 与 toolCalls
 * （assistant 的工具调用段）；tool 携带 toolCallId 与结果文本（isError 时文本即错误说明）。
 *
 * <p>images 是多模态装载缝隙（装载与投影各管一段）：投影（TranscriptProjector）决定
 * 该条消息是否带原图（锚定区内装 storedRef、主模型无视觉则一律不装），翻译器只负责
 * 把装入的 images 转方言形态（openai-compat 的 image_url data URI、Gemini 的
 * inlineData），无自己的降级逻辑——投影出什么形态翻译器就装什么形态。
 */
public record LlmMessage(
        String role,
        String text,
        List<AssistantMessage.ToolCallSegment> toolCalls,
        String toolCallId,
        boolean isError,
        List<ImageContent> images
) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static LlmMessage user(String text) {
        return new LlmMessage(ROLE_USER, text, List.of(), null, false, List.of());
    }

    public static LlmMessage assistant(String text, List<AssistantMessage.ToolCallSegment> toolCalls) {
        return new LlmMessage(ROLE_ASSISTANT, text, toolCalls, null, false, List.of());
    }

    public static LlmMessage toolResult(String toolCallId, String text, boolean isError) {
        return new LlmMessage(ROLE_TOOL, text, List.of(), toolCallId, isError, List.of());
    }
}
