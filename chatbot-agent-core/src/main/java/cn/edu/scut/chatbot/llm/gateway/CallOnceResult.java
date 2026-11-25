package cn.edu.scut.chatbot.llm.gateway;

import cn.edu.scut.chatbot.loop.message.AssistantMessage;

/**
 * callOnce 单次调用结果（contracts/model-gateway.md 第 1 节）：文本 + 用量 + 失败形态。
 * 内部仍走翻译器五步与事件流（外收集 Completed）——非独立协议。
 */
public record CallOnceResult(
        String text,
        AssistantMessage.TokenUsage usage,
        boolean failed,
        String errorCode,
        String errorMessage
) {

    public static CallOnceResult success(String text, AssistantMessage.TokenUsage usage) {
        return new CallOnceResult(text, usage, false, null, null);
    }

    public static CallOnceResult failure(String errorCode, String errorMessage) {
        return new CallOnceResult(null, null, true, errorCode, errorMessage);
    }
}
