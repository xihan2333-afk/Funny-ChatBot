package cn.edu.scut.chatbot.loop;

import java.util.Map;

/**
 * 模型生成参数（ModelRequest 组成部分）：方言无关的公共参数 + passthrough 透传
 * （方言特有参数原样透给翻译器）。
 */
public record GenerationParams(
        double temperature,
        int maxOutputTokens,
        boolean structuredOutput,
        Map<String, String> passthrough
) {

    public static GenerationParams defaults() {
        return new GenerationParams(0.8, 2048, false, Map.of());
    }
}
