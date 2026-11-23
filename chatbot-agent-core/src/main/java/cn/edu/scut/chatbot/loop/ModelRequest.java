package cn.edu.scut.chatbot.loop;

import java.util.List;

/**
 * 模型调用请求（契约 loop-kernel.md 第 1 节）：统一消息投影、工具声明、生成参数、
 * 已解析凭证——凭证从哪来（resolveCredentials 钩子产出）翻译器不管。
 */
public record ModelRequest(
        List<LlmMessage> messages,
        String systemPrompt,
        List<String> toolDeclarations,
        GenerationParams params,
        String resolvedCredential
) {
}
