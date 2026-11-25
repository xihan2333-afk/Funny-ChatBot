package cn.edu.scut.chatbot.llm.dialect.openai;

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
 * openai-compat 方言翻译器：智谱 / DeepSeek / 豆包 / OpenAI 四家共用——发送侧无任何
 * 供应商分支（URL = {baseUrl}/chat/completions、鉴权 Bearer），四家差异全部在接收侧
 * 多字段探测抹平（reasoning_content 思考流、双缓存字段名、index 分片）。
 *
 * <p>五步骨架：组装请求 → StreamExchange 发送（连接级重试一次）→ SseParser 切分 →
 * 流事件归一（块边界合成——方言无显式块标记，翻译器把隐式结构显式化）→ 终止。
 * 思考内容两条契约：ThinkingDelta 实时推出（活跃信号），累积时丢弃——不进
 * AssistantMessage 不进转录。单个 SSE 事件 JSON 非法：丢弃该分片记告警流继续
 * （单分片损坏不杀整流）。
 */
@Component
public class OpenAiCompatTranslator implements DialectTranslator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatTranslator.class);

    private static final ObjectMapper JSON = JsonMappers.shared();

    private final StreamExchange exchange;

    private final StoredMediaFetcher mediaFetcher;

    private final Clock clock;

    private final LongSupplier idSupplier;

    public OpenAiCompatTranslator(StreamExchange exchange, StoredMediaFetcher mediaFetcher,
                                  Clock clock, cn.edu.scut.chatbot.bootstrap.IdGenerator idGenerator) {
        this.exchange = exchange;
        this.mediaFetcher = mediaFetcher;
        this.clock = clock;
        this.idSupplier = idGenerator::nextId;
    }

    @Override
    public String dialect() {
        return ModelRef.DIALECT_OPENAI_COMPAT;
    }

    @Override
    public void translate(TranslateContext context) {
        ModelRequest request = context.request();
        ModelRef model = request.model();
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(model.baseUrl() + "/chat/completions"))
                .timeout(exchange.readTimeout())
                .header("Authorization", "Bearer " + nullSafe(request.resolvedCredential()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request)))
                .build();
        FlowAssembler assembler = new FlowAssembler(context, clock.instant(), idSupplier.getAsLong());
        exchange.exchange(httpRequest, assembler, context.signal());
    }

    // =====================================================================
    // 第一步：请求组装（统一形态 → openai-compat 方言）
    // =====================================================================

    /**
     * 组装 /chat/completions 请求体（passthrough 顶层原样合并不解释——思考开关等
     * 供应商私有参数的兜底通道）
     */
    String buildRequestBody(ModelRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", request.model().modelId());
        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        for (LlmMessage message : request.messages()) {
            appendMessage(messages, message);
        }
        GenerationParams params = request.params();
        body.put("temperature", params.temperature());
        body.put("max_tokens", params.maxOutputTokens());
        if (!request.toolDeclarations().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (String declaration : request.toolDeclarations()) {
                appendToolDeclaration(tools, declaration);
            }
        }
        if (params.structuredOutput()) {
            ObjectNode responseFormat = body.putObject("response_format");
            responseFormat.put("type", "json_object");
        }
        mergePassthrough(body, params.passthrough());
        return body.toString();
    }

    private void appendMessage(ArrayNode messages, LlmMessage message) {
        switch (message.role() == null ? "" : message.role()) {
            case LlmMessage.ROLE_USER -> {
                ObjectNode node = messages.addObject();
                node.put("role", "user");
                if (message.images().isEmpty()) {
                    node.put("content", nullSafe(message.text()));
                } else {
                    // 多模态：文本 + 图片混排数组形态（image_url 为 base64 data URI）
                    ArrayNode content = node.putArray("content");
                    content.addObject().put("type", "text").put("text", nullSafe(message.text()));
                    for (ImageContent image : message.images()) {
                        appendImageUrlPart(content, image);
                    }
                }
            }
            case LlmMessage.ROLE_ASSISTANT -> {
                ObjectNode node = messages.addObject();
                node.put("role", "assistant");
                if (message.text() != null && !message.text().isEmpty()) {
                    node.put("content", message.text());
                }
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    ArrayNode toolCalls = node.putArray("tool_calls");
                    for (AssistantMessage.ToolCallSegment call : message.toolCalls()) {
                        ObjectNode callNode = toolCalls.addObject();
                        callNode.put("id", call.callId());
                        callNode.put("type", "function");
                        ObjectNode function = callNode.putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", call.argumentsJson());
                    }
                }
            }
            case LlmMessage.ROLE_TOOL -> {
                ObjectNode node = messages.addObject();
                node.put("role", "tool");
                node.put("tool_call_id", nullSafe(message.toolCallId()));
                node.put("content", nullSafe(message.text()));
            }
            default -> log.warn("未知角色消息跳过组装 role={}", message.role());
        }
    }

    /**
     * 图片段装载为 image_url data URI（取不到字节跳过该图——装载与投影各管一段，
     * 投影给了什么翻译器就装什么，装不上降级不阻塞调用）
     */
    private void appendImageUrlPart(ArrayNode content, ImageContent image) {
        try {
            StoredMediaFetcher.MediaBytes bytes = mediaFetcher.fetch(image.storedRef());
            if (bytes == null) {
                log.warn("图片字节获取失败，跳过装载 storedRef={}", image.storedRef());
                return;
            }
            String dataUri = "data:" + bytes.mimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes.data());
            ObjectNode part = content.addObject();
            part.put("type", "image_url");
            part.putObject("image_url").put("url", dataUri);
        } catch (Exception e) {
            log.warn("图片装载异常，跳过 storedRef={}", image.storedRef(), e);
        }
    }

    /**
     * 工具声明：AgentLoop 的紧凑声明 JSON（name/description/parameters）→
     * tools[].function 同名结构直取
     */
    private void appendToolDeclaration(ArrayNode tools, String declaration) {
        try {
            JsonNode node = JSON.readTree(declaration);
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.set("name", node.path("name"));
            function.set("description", node.path("description"));
            function.set("parameters", node.path("parameters"));
        } catch (Exception e) {
            log.warn("工具声明解析失败跳过（非法 JSON 不杀请求）", e);
        }
    }

    /**
     * passthrough 顶层原样合并（后写覆盖——同名公共参数以 passthrough 为准，
     * 运营显式给定的私有参数优先）
     */
    private void mergePassthrough(ObjectNode body, Map<String, String> passthrough) {
        if (passthrough == null || passthrough.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : passthrough.entrySet()) {
            try {
                body.set(entry.getKey(), JSON.readTree(entry.getValue()));
            } catch (Exception e) {
                // 叶子值不是合法 JSON（纯字符串数字等）：按字符串字面量放
                body.put(entry.getKey(), entry.getValue());
            }
        }
    }

    // =====================================================================
    // 第二~五步：发送 + SSE 归一 + 终止（FlowAssembler 承载流状态机）
    // =====================================================================

    /**
     * 流归一状态机（每次调用局部——无状态单例 Bean 的纪律）：块边界合成 + 用量探测 +
     * 终止兜底。文本 → 工具调用 → 文本交错的块序天然保留（事件序列即结构）。
     */
    private final class FlowAssembler implements StreamExchange.ExchangeHandler {

        private final TranslateContext context;

        private final Instant occurredAt;

        private final long messageId;

        private final StringBuilder textBuffer = new StringBuilder();

        private final Map<Integer, String> callIds = new LinkedHashMap<>();

        private final Map<Integer, String> callNames = new LinkedHashMap<>();

        private final Map<Integer, StringBuilder> callArgs = new LinkedHashMap<>();

        private final List<AssistantMessage.ToolCallSegment> finishedCalls = new java.util.ArrayList<>();

        private AssistantMessage.TokenUsage usage;

        private String finishReason;

        private boolean thinkingOpen;

        private boolean textOpen;

        /**
         * 打开中的工具调用块（首现序）——并行多工具交错分片时兄弟块同时打开，
         * 参数按 index 各自归并，终止时按首现序统一闭合
         */
        private final java.util.LinkedHashSet<Integer> openToolIndexes = new java.util.LinkedHashSet<>();

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
            log.error("openai-compat 上游错误 errorCode={} status={} traceId=n/a",
                    error.errorCode(), status);
            terminate(new ModelStreamEvent.Failed(error.errorCode(), error.errorMessage()));
        }

        @Override
        public void onSseData(String data) {
            if (terminated) {
                return;
            }
            if ("[DONE]".equals(data)) {
                // [DONE] 哨兵终止：闭合全部打开块并推 Completed（finish_reason 缺失也兜底）
                finish();
                return;
            }
            JsonNode node;
            try {
                node = JSON.readTree(data);
            } catch (Exception e) {
                // 单分片损坏不杀整流（丢弃记告警继续）
                log.warn("SSE 分片 JSON 非法丢弃（流继续）：{}", abbreviate(data));
                return;
            }
            JsonNode delta = node.path("choices").path(0).path("delta");
            if (delta.isObject()) {
                consumeDelta(delta);
            }
            JsonNode finishNode = node.path("choices").path(0).path("finish_reason");
            if (finishNode.isTextual() && !finishNode.asText().isEmpty()) {
                // finish_reason 到达：闭合全部打开块（Completed 等 [DONE]/流关闭时带用量推）
                this.finishReason = finishNode.asText();
                closeAllBlocks();
            }
            JsonNode usageNode = node.path("usage");
            if (usageNode.isObject()) {
                this.usage = normalizeUsage(usageNode);
            }
        }

        @Override
        public void onStreamEnd() {
            // 流关闭终止（[DONE] 缺失的兜底——配对自校验通过）
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

        // ===== 增量消费（块边界合成三触发） =====

        private void consumeDelta(JsonNode delta) {
            JsonNode reasoning = delta.path("reasoning_content");
            if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                toThinking();
                push(new ModelStreamEvent.ThinkingDelta(reasoning.asText()));
                return;
            }
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                toText();
                textBuffer.append(content.asText());
                push(new ModelStreamEvent.TextDelta(content.asText()));
                return;
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (Iterator<JsonNode> it = toolCalls.elements(); it.hasNext(); ) {
                    consumeToolCallFragment(it.next());
                }
            }
        }

        private void consumeToolCallFragment(JsonNode fragment) {
            int index = fragment.path("index").asInt(0);
            JsonNode function = fragment.path("function");
            String callId = fragment.path("id").asText(null);
            String name = function.path("name").asText(null);
            String args = function.path("arguments").asText("");
            if (!callIds.containsKey(index)) {
                // 新 index 首个分片：闭合思考/文本块（若有）后开新调用块；
                // 已打开的兄弟调用块保持打开——并行交错分片各自归并
                callIds.put(index, callId);
                callNames.put(index, name);
                callArgs.put(index, new StringBuilder());
                closeTextAndThinking();
                push(new ModelStreamEvent.ToolCallStart(index,
                        nullSafe(callId), nullSafe(name)));
                openToolIndexes.add(index);
            } else {
                if (callId != null && !callId.isEmpty() && callIds.get(index) == null) {
                    callIds.put(index, callId);
                }
            }
            if (!args.isEmpty()) {
                callArgs.get(index).append(args);
                push(new ModelStreamEvent.ToolCallDelta(index, args));
            }
        }

        // ===== 块切换（隐式结构显式化：End 总是配对同 index 的 Start） =====

        private void toThinking() {
            if (textOpen) {
                push(new ModelStreamEvent.TextEnd());
                textOpen = false;
            }
            closeOpenToolCalls();
            if (!thinkingOpen) {
                push(new ModelStreamEvent.ThinkingStart());
                thinkingOpen = true;
            }
        }

        private void toText() {
            if (thinkingOpen) {
                push(new ModelStreamEvent.ThinkingEnd());
                thinkingOpen = false;
            }
            closeOpenToolCalls();
            if (!textOpen) {
                push(new ModelStreamEvent.TextStart());
                textOpen = true;
            }
        }

        private void closeTextAndThinking() {
            if (thinkingOpen) {
                push(new ModelStreamEvent.ThinkingEnd());
                thinkingOpen = false;
            }
            if (textOpen) {
                push(new ModelStreamEvent.TextEnd());
                textOpen = false;
            }
        }

        /**
         * 关闭全部打开中的工具调用块（按首现序——被文本/思考块打断或终止时）
         */
        private void closeOpenToolCalls() {
            for (Integer index : new java.util.ArrayList<>(openToolIndexes)) {
                closeToolCall(index);
            }
        }

        private void closeToolCall(int index) {
            push(new ModelStreamEvent.ToolCallEnd(index));
            finishedCalls.add(new AssistantMessage.ToolCallSegment(
                    nullSafe(callIds.get(index)), nullSafe(callNames.get(index)),
                    callArgs.get(index).toString()));
            openToolIndexes.remove(index);
        }

        private void closeAllBlocks() {
            closeTextAndThinking();
            closeOpenToolCalls();
        }

        // ===== 终止 =====

        private void finish() {
            closeAllBlocks();
            boolean hasOutput = textBuffer.length() > 0 || !finishedCalls.isEmpty();
            if (ErrorNormalizer.emptyLengthStopOverflow(finishReason, hasOutput)) {
                // 三重探测第三路：空输出且长度原因停止（上层应急压缩通道处置）
                terminate(new ModelStreamEvent.Failed(LlmErrorCodes.CONTEXT_OVERFLOW,
                        "空输出且以长度原因停止 finish_reason=" + finishReason));
                return;
            }
            String stopReason = "tool_calls".equals(finishReason)
                    ? AssistantMessage.STOP_REASON_TOOL_USE : AssistantMessage.STOP_REASON_STOP;
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

    // =====================================================================
    // 用量归一（contracts/dialect-translators.md 第 4 节）
    // =====================================================================

    /**
     * 用量探测：cached 双字段名（prompt_tokens_details.cached_tokens 与
     * prompt_cache_hit_tokens 命中即取）；含思考口径原样不折算。
     */
    static AssistantMessage.TokenUsage normalizeUsage(JsonNode usage) {
        int input = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
        int cached = 0;
        JsonNode cachedNode = usage.path("prompt_tokens_details").path("cached_tokens");
        if (!cachedNode.isMissingNode() && cachedNode.canConvertToInt()) {
            cached = cachedNode.asInt(0);
        } else {
            JsonNode deepseekHit = usage.path("prompt_cache_hit_tokens");
            if (!deepseekHit.isMissingNode() && deepseekHit.canConvertToInt()) {
                cached = deepseekHit.asInt(0);
            }
        }
        int total = usage.path("total_tokens").asInt(input + output);
        return new AssistantMessage.TokenUsage(input, output, reasoning, cached, total);
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
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
