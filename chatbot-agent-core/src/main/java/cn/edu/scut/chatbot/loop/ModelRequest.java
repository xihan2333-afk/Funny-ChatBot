package cn.edu.scut.chatbot.loop;

import java.util.List;

/**
 * 模型调用请求（契约 loop-kernel.md 第 1 节）：统一消息投影、模型运行时对象、工具声明、
 * 生成参数、已解析凭证——凭证从哪来（resolveCredentials 钩子产出）翻译器不管。
 *
 * <p>model 是翻译器路由与 URL 拼装依据（dialect 查注册表、baseUrl/modelId 进请求）；
 * 可为 null（早期桩与剧本假件不读它——ScriptedStreamFn 不受影响）。
 */
public record ModelRequest(
        ModelRef model,
        List<LlmMessage> messages,
        String systemPrompt,
        List<String> toolDeclarations,
        GenerationParams params,
        String resolvedCredential
) {
}
