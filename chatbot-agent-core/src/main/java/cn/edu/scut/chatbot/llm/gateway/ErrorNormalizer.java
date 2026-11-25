package cn.edu.scut.chatbot.llm.gateway;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.llm.slot.LlmErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 统一错误归一组件（contracts/dialect-translators.md 第 5 节）：各家错误表现归一为
 * 八种 errorCode 随 Failed 进流。判定优先级按契约表序——限流 → 鉴权 → 上下文超长 →
 * 内容策略 → 其余 API 错误；同时命中多类时先命中者归一，原始错误文本进 errorMessage
 * 供排查。两方言翻译器的 HTTP 错误路径统一走本组件（替换 US2/US3 的粗归一）。
 *
 * <p>归一只描述「系统哪里坏了」不做决策：应用级错误不重试（重试归上层 Effects），
 * 凭证反馈联动在网关观察 Failed 事件时触发——本组件不感知池。
 */
public final class ErrorNormalizer {

    /**
     * 归一结果：errorCode + 供排查的 errorMessage（含上游原文摘要）
     */
    public record NormalizedError(String errorCode, String errorMessage) {
    }

    /**
     * 长度类错误文案（小写 contains 匹配，含中文——各家超长报文的措辞收集）
     */
    private static final String[] LENGTH_OVERFLOW_PATTERNS = {
            "context length", "context window", "maximum context", "context_length",
            "prompt is too long", "input too long", "too long", "too many tokens",
            "token limit", "tokens limit", "maximum tokens", "max_tokens",
            "exceeds the maximum", "exceeds maximum", "exceed context",
            "上下文长度", "上下文过长", "上下文超出", "长度超出", "长度超限", "超出上限",
            "超过上限", "超出最大", "超过最大", "过长"
    };

    /**
     * 内容策略类错误文案（小写 contains 匹配，含中文）
     */
    private static final String[] CONTENT_POLICY_PATTERNS = {
            "content_policy", "content filter", "content filtering", "safety",
            "safe_completion", "prohibited content", "policy violation",
            "内容安全", "内容策略", "敏感内容", "违规内容"
    };

    private ErrorNormalizer() {
    }

    /**
     * HTTP 错误响应归一：状态码 + 错误体（含 Gemini error.status 枚举形态）+
     * 上下文窗口（token 数对比探测用，null 表示跳过该路探测）。
     */
    public static NormalizedError normalize(int status, String body, Integer contextWindow) {
        String safeBody = body == null ? "" : body;
        String lower = safeBody.toLowerCase(Locale.ROOT);
        String errorStatus = extractGeminiErrorStatus(safeBody);

        if (status == 429 || "RESOURCE_EXHAUSTED".equals(errorStatus)) {
            return new NormalizedError(LlmErrorCodes.RATE_LIMITED, message(status, safeBody));
        }
        if (status == 401 || status == 403
                || "UNAUTHENTICATED".equals(errorStatus) || "PERMISSION_DENIED".equals(errorStatus)) {
            return new NormalizedError(LlmErrorCodes.AUTH, message(status, safeBody));
        }
        if (matchesAny(lower, LENGTH_OVERFLOW_PATTERNS)
                || exceedsContextWindow(safeBody, contextWindow)) {
            return new NormalizedError(LlmErrorCodes.CONTEXT_OVERFLOW, message(status, safeBody));
        }
        if (errorStatus.startsWith("SAFETY") || matchesAny(lower, CONTENT_POLICY_PATTERNS)) {
            return new NormalizedError(LlmErrorCodes.CONTENT_POLICY, message(status, safeBody));
        }
        return new NormalizedError(LlmErrorCodes.API_ERROR, message(status, safeBody));
    }

    /**
     * 流内空输出长度停止探测（三重探测的第三路）：正文与工具调用皆为空且
     * finish_reason 以长度原因停止（openai 阵营 length，豆包/Gemini 阵营 max_tokens）
     * → 判上下文超长。
     */
    public static boolean emptyLengthStopOverflow(String finishReason, boolean hasOutput) {
        if (hasOutput || finishReason == null || finishReason.isBlank()) {
            return false;
        }
        String reason = finishReason.toLowerCase(Locale.ROOT);
        return reason.contains("length") || reason.contains("max_tokens");
    }

    /**
     * Gemini 错误体的 error.status 枚举（openai 阵营错误体无此形态，返回空串）
     */
    private static String extractGeminiErrorStatus(String body) {
        try {
            JsonNode root = JsonMappers.shared().readTree(body);
            JsonNode status = root.path("error").path("status");
            return status.isTextual() ? status.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * token 数对比探测（三重探测的第二路）：错误体携带 prompt_tokens 且超过
     * 模型上下文窗口。错误体非 JSON 或不带该字段时不命中。
     */
    private static boolean exceedsContextWindow(String body, Integer contextWindow) {
        if (contextWindow == null) {
            return false;
        }
        try {
            JsonNode root = JsonMappers.shared().readTree(body);
            JsonNode promptTokens = root.path("usage").path("prompt_tokens");
            if (!promptTokens.isNumber()) {
                promptTokens = root.path("prompt_tokens");
            }
            return promptTokens.isNumber() && promptTokens.asInt() > contextWindow;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean matchesAny(String lowerText, String[] patterns) {
        for (String pattern : patterns) {
            if (lowerText.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String message(int status, String body) {
        String abbreviated = body.length() <= 300 ? body : body.substring(0, 300) + "...";
        return "上游错误 HTTP " + status + "：" + abbreviated;
    }
}
