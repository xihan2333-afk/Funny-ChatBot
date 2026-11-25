package cn.edu.scut.chatbot.llm.dialect.gemini;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.llm.gateway.*;
import cn.edu.scut.chatbot.llm.slot.LlmErrorCodes;
import cn.edu.scut.chatbot.loop.*;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.ImageContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Gemini 原生方言翻译器：与 openai-compat 五步骨架同构，差异全部在翻译器内
 * （contracts/dialect-translators.md 差异表逐项）——systemInstruction 独立字段
 * （无 system 角色）、user/model 角色映射、上一轮工具调用进 model 消息 functionCall
 * part、本轮工具结果进 user 消息 functionResponse part、thought 标记产思考事件、
 * inlineData 多模态、generationConfig 收参数、x-goog-api-key 鉴权、
 * :streamGenerateContent?alt=sse 端点。
 *
 * <p>接收侧 parts 天然带块边界（直接映射三段式，无需像 openai-compat 那样合成块
 * 边界）；流关闭终止（无 [DONE] 哨兵）；思考内容累积时丢弃纪律同 openai-compat。
 */
@Component
public class GeminiTranslator implements DialectTranslator {

    private static final Logger log = LoggerFactory.getLogger(GeminiTranslator.class);

    private static final ObjectMapper JSON = JsonMappers.shared();

    private final StreamExchange exchange;

    private final StoredMediaFetcher mediaFetcher;

    private final Clock clock;

    private final LongSupplier idSupplier;

    public GeminiTranslator(StreamExchange exchange, StoredMediaFetcher mediaFetcher,
                            Clock clock, cn.edu.scut.chatbot.bootstrap.IdGenerator idGenerator) {
        this.exchange = exchange;
        this.mediaFetcher = mediaFetcher;
        this.clock = clock;
        this.idSupplier = idGenerator::nextId;
    }

    @Override
    public String dialect() {
        return ModelRef.DIALECT_GEMINI;
    }

    @Override
    public void translate(TranslateContext context) {
        ModelRequest request = context.request();
        ModelRef model = request.model();
        String url = model.baseUrl() + "/models/" + model.modelId() + ":streamGenerateContent?alt=sse";
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(exchange.readTimeout())
                .header("x-goog-api-key", request.resolvedCredential() != null
                        ? request.resolvedCredential() : "")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request)))
                .build();
        FlowAssembler assembler = new FlowAssembler(context, clock.instant(), idSupplier.getAsLong());
        exchange.exchange(httpRequest, assembler, context.signal());
    }

    // =====================================================================
    // 第一步：请求组装（统一形态 → gemini 方言）
    // =====================================================================

    String buildRequestBody(ModelRequest request) {
        ObjectNode body = JSON.createObjectNode();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            // systemPrompt → 独立 systemInstruction 字段（gemini 无 system 角色）
            body.putObject("systemInstruction").putArray("parts").addObject()
                    .put("text", request.systemPrompt());
        }
        // callId → 工具名反查表（functionResponse 需要 name，统一消息里只有 toolCallId）
        Map<String, String> toolNameByCallId = new HashMap<>();
        for (LlmMessage message : request.messages()) {
            if (message.toolCalls() != null) {
                for (AssistantMessage.ToolCallSegment call : message.toolCalls()) {
                    toolNameByCallId.put(call.callId(), call.name());
                }
            }
        }
        ArrayNode contents = body.putArray("contents");
        for (LlmMessage message : request.messages()) {
            appendContent(contents, message, toolNameByCallId);
        }
        GenerationParams params = request.params();
        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("temperature", params.temperature());
        generationConfig.put("maxOutputTokens", params.maxOutputTokens());
        if (params.structuredOutput()) {
            generationConfig.put("responseMimeType", "application/json");
        }
        if (!request.toolDeclarations().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            ArrayNode declarations = tool.putArray("functionDeclarations");
            for (String declaration : request.toolDeclarations()) {
                appendFunctionDeclaration(declarations, declaration);
            }
        }
        mergePassthrough(body, generationConfig, params.passthrough());
        return body.toString();
    }

    private void appendContent(ArrayNode contents, LlmMessage message,
                               Map<String, String> toolNameByCallId) {
        switch (message.role() == null ? "" : message.role()) {
            case LlmMessage.ROLE_USER -> {
                ObjectNode node = contents.addObject();
                node.put("role", "user");
                ArrayNode parts = node.putArray("parts");
                if (message.text() != null && !message.text().isEmpty()) {
                    parts.addObject().put("text", message.text());
                }
                for (ImageContent image : message.images()) {
                    appendInlineDataPart(parts, image);
                }
            }
            case LlmMessage.ROLE_ASSISTANT -> {
                ObjectNode node = contents.addObject();
                node.put("role", "model");
                ArrayNode parts = node.putArray("parts");
                if (message.text() != null && !message.text().isEmpty()) {
                    parts.addObject().put("text", message.text());
                }
                if (message.toolCalls() != null) {
                    for (AssistantMessage.ToolCallSegment call : message.toolCalls()) {
                        ObjectNode functionCall = parts.addObject().putObject("functionCall");
                        functionCall.put("name", call.name());
                        functionCall.set("args", parseArgs(call.argumentsJson()));
                    }
                }
            }
            case LlmMessage.ROLE_TOOL -> {
                ObjectNode node = contents.addObject();
                node.put("role", "user");
                ObjectNode functionResponse = node.putArray("parts").addObject()
                        .putObject("functionResponse");
                functionResponse.put("name", toolNameByCallId.getOrDefault(
                        message.toolCallId(), "unknownTool"));
                ObjectNode response = functionResponse.putObject("response");
                response.put("result", message.text() != null ? message.text() : "");
            }
            default -> log.warn("未知角色消息跳过组装 role={}", message.role());
        }
    }

    /**
     * 图片段装载为 inlineData {mimeType, data(base64)}（取不到字节跳过——
     * 装载与投影各管一段纪律同 openai-compat）
     */
    private void appendInlineDataPart(ArrayNode parts, ImageContent image) {
        try {
            StoredMediaFetcher.MediaBytes bytes = mediaFetcher.fetch(image.storedRef());
            if (bytes == null) {
                log.warn("图片字节获取失败，跳过装载 storedRef={}", image.storedRef());
                return;
            }
            ObjectNode inline = parts.addObject().putObject("inlineData");
            inline.put("mimeType", bytes.mimeType());
            inline.put("data", Base64.getEncoder().encodeToString(bytes.data()));
        } catch (Exception e) {
            log.warn("图片装载异常，跳过 storedRef={}", image.storedRef(), e);
        }
    }

    private void appendFunctionDeclaration(ArrayNode declarations, String declaration) {
        try {
            JsonNode node = JSON.readTree(declaration);
            ObjectNode function = declarations.addObject();
            function.set("name", node.path("name"));
            function.set("description", node.path("description"));
            function.set("parameters", node.path("parameters"));
        } catch (Exception e) {
            log.warn("工具声明解析失败跳过（非法 JSON 不杀请求）", e);
        }
    }

    /**
     * passthrough 原样合并：generationConfig 与顶层都可（generationConfig.* 键进
     * generationConfig，其余进顶层——叶子值按 JSON 或字符串字面量）
     */
    private void mergePassthrough(ObjectNode body, ObjectNode generationConfig,
                                  Map<String, String> passthrough) {
        if (passthrough == null || passthrough.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : passthrough.entrySet()) {
            String key = entry.getKey();
            JsonNode value;
            try {
                value = JSON.readTree(entry.getValue());
            } catch (Exception e) {
                value = JSON.getNodeFactory().textNode(entry.getValue());
            }
            if (key.startsWith("generationConfig.")) {
                generationConfig.set(key.substring("generationConfig.".length()), value);
            } else {
                body.set(key, value);
            }
        }
    }

    private JsonNode parseArgs(String argumentsJson) {
        try {
            JsonNode parsed = JSON.readTree(argumentsJson == null || argumentsJson.isEmpty()
                    ? "{}" : argumentsJson);
            return parsed.isObject() ? parsed : JSON.createObjectNode();
        } catch (Exception e) {
            log.warn("工具参数 JSON 非法，按空对象装载", e);
            return JSON.createObjectNode();
        }
    }

    // =====================================================================
    // 第二~五步：发送 + SSE 归一 + 终止
    // =====================================================================

    /**
     * 流归一状态机（parts 天然块边界直接映射三段式；每次调用局部）
     */
    private final class FlowAssembler implements StreamExchange.ExchangeHandler {

        private final TranslateContext context;

        private final Instant occurredAt;

        private final long messageId;

        private final StringBuilder textBuffer = new StringBuilder();

        private final List<AssistantMessage.ToolCallSegment> finishedCalls = new java.util.ArrayList<>();

        private AssistantMessage.TokenUsage usage;

        private String finishReason;

        private int nextCallIndex = 0;

        private boolean thinkingOpen;

        private boolean textOpen;

        private boolean terminated;

        FlowAssembler(TranslateContext context, Instant occurredAt, long messageId) {
            this.context = context;
            this.occurredAt = occurredAt;
            this.messageId = messageId;
        }

        @Override
        public void onHttpError(int status, String contentType, String body) {
            ErrorNormalizer.NormalizedError error = ErrorNormalizer.normalize(status, body,
                    contextWindowOf(context));
            log.error("gemini 上游错误 errorCode={} status={} traceId=n/a",
                    error.errorCode(), status);
            terminate(new ModelStreamEvent.Failed(error.errorCode(), error.errorMessage()));
        }

        @Override
        public void onSseData(String data) {
            if (terminated) {
                return;
            }
            if ("[DONE]".equals(data)) {
                // gemini 正常无 [DONE] 哨兵；意外到达按流终止处理（宽容）
                finish();
                return;
            }
            JsonNode node;
            try {
                node = JSON.readTree(data);
            } catch (Exception e) {
                log.warn("SSE 分片 JSON 非法丢弃（流继续）：{}", abbreviate(data));
                return;
            }
            // 安全拦截形态（无 candidates/parts，只有 promptFeedback.blockReason）与
            // finishReason=SAFETY——归一 content_policy 而不是产出空文本 Completed
            String blockReason = node.path("promptFeedback").path("blockReason").asText("");
            JsonNode finishNode = node.path("candidates").path(0).path("finishReason");
            if (!blockReason.isEmpty()
                    || (finishNode.isTextual() && finishNode.asText().startsWith("SAFETY"))) {
                log.error("gemini 安全拦截 errorCode={} blockReason={} traceId=n/a",
                        LlmErrorCodes.CONTENT_POLICY, blockReason);
                terminate(new ModelStreamEvent.Failed(LlmErrorCodes.CONTENT_POLICY,
                        "内容被安全策略拦截 blockReason=" + blockReason));
                return;
            }
            JsonNode parts = node.path("candidates").path(0).path("content").path("parts");
            if (parts.isArray()) {
                for (Iterator<JsonNode> it = parts.elements(); it.hasNext(); ) {
                    consumePart(it.next());
                }
            }
            if (finishNode.isTextual() && !finishNode.asText().isEmpty()) {
                this.finishReason = finishNode.asText();
                closeAllBlocks();
            }
            JsonNode usageNode = node.path("usageMetadata");
            if (usageNode.isObject()) {
                this.usage = normalizeUsage(usageNode);
            }
        }

        @Override
        public void onStreamEnd() {
            // gemini 的正常终止方式：流关闭（finishReason 已在末 chunk 给出）
            if (!terminated) {
                finish();
            }
        }

        @Override
        public void onIoFailure(String errorCode, String message) {
            terminate(new ModelStreamEvent.Failed(errorCode, message));
        }

        @Override
        public void onAborted() {
            terminate(new ModelStreamEvent.Failed(LlmErrorCodes.ABORTED, "取消信号置位，停止读流"));
        }

        private void consumePart(JsonNode part) {
            JsonNode functionCall = part.path("functionCall");
            if (functionCall.isObject()) {
                closeTextAndThinking();
                // functionCall part 整块到达：三段式整段交付（无需按 index 累积）
                int index = nextCallIndex++;
                String callId = "call-" + Long.toUnsignedString(idSupplier.getAsLong());
                String name = functionCall.path("name").asText("");
                String argsJson = functionCall.path("args").toString();
                push(new ModelStreamEvent.ToolCallStart(index, callId, name));
                push(new ModelStreamEvent.ToolCallDelta(index, argsJson));
                push(new ModelStreamEvent.ToolCallEnd(index));
                finishedCalls.add(new AssistantMessage.ToolCallSegment(callId, name, argsJson));
                return;
            }
            JsonNode text = part.path("text");
            if (!text.isTextual() || text.asText().isEmpty()) {
                return;
            }
            boolean thought = part.path("thought").asBoolean(false);
            if (thought) {
                closeTextAndThinking();
                if (!thinkingOpen) {
                    push(new ModelStreamEvent.ThinkingStart());
                    thinkingOpen = true;
                }
                push(new ModelStreamEvent.ThinkingDelta(text.asText()));
                // 思考内容累积时丢弃：不进 textBuffer、不进 AssistantMessage
                return;
            }
            closeThinking();
            if (!textOpen) {
                push(new ModelStreamEvent.TextStart());
                textOpen = true;
            }
            textBuffer.append(text.asText());
            push(new ModelStreamEvent.TextDelta(text.asText()));
        }

        private void closeThinking() {
            if (thinkingOpen) {
                push(new ModelStreamEvent.ThinkingEnd());
                thinkingOpen = false;
            }
        }

        private void closeTextAndThinking() {
            closeThinking();
            if (textOpen) {
                push(new ModelStreamEvent.TextEnd());
                textOpen = false;
            }
        }

        private void closeAllBlocks() {
            closeTextAndThinking();
        }

        private void finish() {
            closeAllBlocks();
            boolean hasOutput = textBuffer.length() > 0 || !finishedCalls.isEmpty();
            if (ErrorNormalizer.emptyLengthStopOverflow(finishReason, hasOutput)) {
                // 三重探测第三路：空输出且长度原因停止（上层应急压缩通道处置）
                terminate(new ModelStreamEvent.Failed(LlmErrorCodes.CONTEXT_OVERFLOW,
                        "空输出且以长度原因停止 finishReason=" + finishReason));
                return;
            }
            // gemini 的 functionCall 流 finishReason 也是 STOP——按有无工具调用段判 toolUse
            String stopReason = finishedCalls.isEmpty()
                    ? AssistantMessage.STOP_REASON_STOP : AssistantMessage.STOP_REASON_TOOL_USE;
            AssistantMessage message = new AssistantMessage(
                    Long.toUnsignedString(messageId), occurredAt, 60, null,
                    textBuffer.toString(), List.copyOf(finishedCalls), stopReason, usage);
            terminate(new ModelStreamEvent.Completed(message));
        }

        private void terminate(ModelStreamEvent terminal) {
            if (terminated) {
                return;
            }
            terminated = true;
            push(terminal);
        }

        private void push(ModelStreamEvent event) {
            context.consumer().onEvent(event);
        }
    }

    /**
     * 用量归一：promptTokenCount / candidatesTokenCount / totalTokenCount /
     * thoughtsTokenCount（思考） / cachedContentTokenCount（缓存命中，无则 0）
     */
    static AssistantMessage.TokenUsage normalizeUsage(JsonNode usage) {
        int input = usage.path("promptTokenCount").asInt(0);
        int output = usage.path("candidatesTokenCount").asInt(0);
        int reasoning = usage.path("thoughtsTokenCount").asInt(0);
        int cached = usage.path("cachedContentTokenCount").asInt(0);
        int total = usage.path("totalTokenCount").asInt(input + output);
        return new AssistantMessage.TokenUsage(input, output, reasoning, cached, total);
    }

    /**
     * token 数对比探测的上下文窗口（capabilities 缺失时返回 null 跳过该路探测）
     */
    private static Integer contextWindowOf(TranslateContext context) {
        if (context.request().model() == null || context.request().model().capabilities() == null) {
            return null;
        }
        return context.request().model().capabilities().contextWindow();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
