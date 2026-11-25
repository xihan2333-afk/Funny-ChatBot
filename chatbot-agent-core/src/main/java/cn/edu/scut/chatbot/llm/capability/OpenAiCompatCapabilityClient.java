package cn.edu.scut.chatbot.llm.capability;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder;
import cn.edu.scut.chatbot.llm.gateway.ErrorNormalizer;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.slot.CredentialPool;
import cn.edu.scut.chatbot.llm.slot.LlmErrorCodes;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.SlotResolver;
import cn.edu.scut.chatbot.loop.ModelRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * openai-compat 能力端点客户端（contracts/capability-api.md 第 1 节）：images/generations、
 * audio/speech、embeddings 三端点，一个类实现三接口（Gemini 对应端点差异以实现内分派，
 * 第一版以 openai-compat 形态为准）。同步阻塞（虚拟线程等得起）。
 *
 * <p>与对话翻译器同构的纪律：槽位解析 + 凭证池、Bearer 鉴权、连接级静默重试一次、
 * 错误经 ErrorNormalizer 归一（llm.* 点分码随异常抛给消费方——工具侧转 isError，
 * 阅读侧降级启发式）。字节上限默认 20MB（system_param {@code llm.capability.max_bytes}
 * 可调），超限截断判错（防内存放大）。
 */
@Component
public class OpenAiCompatCapabilityClient
        implements ImageGenerator, SpeechSynthesizer, EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatCapabilityClient.class);

    /**
     * 字节产物上限 system_param 键（缺省 20MB）
     */
    public static final String MAX_BYTES_PARAM = "llm.capability.max_bytes";

    private static final int DEFAULT_MAX_BYTES = 20 * 1024 * 1024;

    private final HttpClientHolder holder;

    private final SlotResolver slotResolver;

    private final CredentialPool credentialPool;

    private final SystemParamService systemParams;

    private final cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder;

    public OpenAiCompatCapabilityClient(HttpClientHolder holder, SlotResolver slotResolver,
                                        CredentialPool credentialPool,
                                        SystemParamService systemParams,
                                        cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder) {
        this.holder = holder;
        this.slotResolver = slotResolver;
        this.credentialPool = credentialPool;
        this.systemParams = systemParams;
        this.costRecorder = costRecorder;
    }

    /**
     * 能力调用失败（槽位未绑定 / 无凭证 / 上游错误归一码 / 字节超限）——
     * 消费方按各自路径处置（工具 isError、阅读降级）
     */
    public static final class CapabilityException extends RuntimeException {

        private final String errorCode;

        public CapabilityException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    /**
     * 能力调用计量（同步接口无增量事件——TTFT 记 0、无用量块；失败也落账。
     * run_kind 经 meta.runKind() 进落账——调用方传 LlmCallMeta.of("image"/...) ）
     */
    private void recordCall(LlmCallMeta meta, ModelRef model, long startNanos, String errorCode) {
        try {
            long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
            costRecorder.record(meta, model, new cn.edu.scut.chatbot.llm.slot.CostSample(
                    null, 0, totalMs,
                    errorCode == null ? "stop" : "error", errorCode));
        } catch (Exception e) {
            // 计量失败不影响调用结果
        }
    }

    @Override
    public ImageResult generate(String slotCode, LlmCallMeta meta,
                                String prompt, ImageOptions options) {
        long startNanos = System.nanoTime();
        ModelRef[] resolved = new ModelRef[1];
        try {
            ImageResult result = doGenerate(slotCode, meta, prompt, options, resolved);
            recordCall(meta, resolved[0], startNanos, null);
            return result;
        } catch (RuntimeException e) {
            recordCall(meta, resolved[0], startNanos, errorCodeOf(e));
            throw e;
        }
    }

    private ImageResult doGenerate(String slotCode, LlmCallMeta meta,
                                   String prompt, ImageOptions options, ModelRef[] resolvedOut) {
        ModelRef model = resolveSlot(slotCode);
        resolvedOut[0] = model;
        ObjectNode body = JsonMappers.shared().createObjectNode();
        body.put("model", model.modelId());
        body.put("prompt", prompt);
        body.put("response_format", "b64_json");
        if (options != null) {
            if (options.size() != null) {
                body.put("size", options.size());
            }
            if (options.quality() != null) {
                body.put("quality", options.quality());
            }
        }
        JsonNode root = postJson(model, "/images/generations", body);
        String b64 = root.path("data").path(0).path("b64_json").asText("");
        if (b64.isEmpty()) {
            throw new CapabilityException(LlmErrorCodes.API_ERROR, "生图响应不含图片数据");
        }
        byte[] image = Base64.getDecoder().decode(b64);
        limitBytes(image.length);
        String mimeType = root.path("data").path(0).path("mime_type").asText("image/png");
        return new ImageResult(image, mimeType);
    }

    @Override
    public VoiceResult synthesize(String slotCode, LlmCallMeta meta,
                                  String text, VoiceOptions options) {
        long startNanos = System.nanoTime();
        ModelRef[] resolved = new ModelRef[1];
        try {
            VoiceResult result = doSynthesize(slotCode, meta, text, options, resolved);
            recordCall(meta, resolved[0], startNanos, null);
            return result;
        } catch (RuntimeException e) {
            recordCall(meta, resolved[0], startNanos, errorCodeOf(e));
            throw e;
        }
    }

    private VoiceResult doSynthesize(String slotCode, LlmCallMeta meta,
                                     String text, VoiceOptions options, ModelRef[] resolvedOut) {
        ModelRef model = resolveSlot(slotCode);
        resolvedOut[0] = model;
        ObjectNode body = JsonMappers.shared().createObjectNode();
        body.put("model", model.modelId());
        body.put("input", text);
        body.put("voice", options != null && options.voice() != null ? options.voice() : "alloy");
        if (options != null && options.speed() != null) {
            body.put("speed", Double.parseDouble(options.speed()));
        }
        body.put("response_format", "mp3");
        byte[] audio = postBytes(model, "/audio/speech", body);
        limitBytes(audio.length);
        return new VoiceResult(audio, "audio/mpeg");
    }

    @Override
    public List<float[]> embed(String slotCode, LlmCallMeta meta, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();   // 空批次短路——不发起调用
        }
        long startNanos = System.nanoTime();
        ModelRef[] resolved = new ModelRef[1];
        try {
            List<float[]> result = doEmbed(slotCode, meta, texts, resolved);
            recordCall(meta, resolved[0], startNanos, null);
            return result;
        } catch (RuntimeException e) {
            recordCall(meta, resolved[0], startNanos, errorCodeOf(e));
            throw e;
        }
    }

    private List<float[]> doEmbed(String slotCode, LlmCallMeta meta, List<String> texts,
                                  ModelRef[] resolvedOut) {
        ModelRef model = resolveSlot(slotCode);
        resolvedOut[0] = model;
        ObjectNode body = JsonMappers.shared().createObjectNode();
        body.put("model", model.modelId());
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        JsonNode root = postJson(model, "/embeddings", body);
        // 按 index 字段归位（供应商返回序不保证与输入一致）
        float[][] vectors = new float[texts.size()][];
        for (JsonNode item : root.path("data")) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= vectors.length) {
                continue;
            }
            ArrayNode vector = (ArrayNode) item.path("embedding");
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                values[i] = (float) vector.get(i).asDouble();
            }
            vectors[index] = values;
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (float[] vector : vectors) {
            if (vector == null) {
                throw new CapabilityException(LlmErrorCodes.API_ERROR, "向量化响应与输入条数不符");
            }
            result.add(vector);
        }
        return result;
    }

    // ===== 共用底座 =====

    private ModelRef resolveSlot(String slotCode) {
        try {
            return slotResolver.resolve(slotCode).model();
        } catch (ModelCatalog.SlotResolutionException e) {
            throw new CapabilityException(LlmErrorCodes.CONFIG_MISSING, e.getMessage());
        }
    }

    private static String errorCodeOf(RuntimeException e) {
        return e instanceof CapabilityException capability ? capability.errorCode() : null;
    }

    private String requireCredential(ModelRef model) {
        String credential = credentialPool.nextAvailable(model.providerCode());
        if (credential == null || credential.isEmpty()) {
            throw new CapabilityException(LlmErrorCodes.CONFIG_MISSING,
                    "供应商无可用凭证 provider=" + model.providerCode());
        }
        return credential;
    }

    private byte[] postBytes(ModelRef model, String path, ObjectNode body) {
        String credential = requireCredential(model);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(model.baseUrl() + path))
                .timeout(holder.readTimeout())
                .header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<byte[]> response = sendWithOneRetry(request);
        if (response.statusCode() / 100 != 2) {
            ErrorNormalizer.NormalizedError error = ErrorNormalizer.normalize(
                    response.statusCode(), new String(response.body(), java.nio.charset.StandardCharsets.UTF_8),
                    model.capabilities() != null ? model.capabilities().contextWindow() : null);
            log.error("能力端点上游错误 errorCode={} status={} path={} traceId=n/a",
                    error.errorCode(), response.statusCode(), path);
            throw new CapabilityException(error.errorCode(), error.errorMessage());
        }
        return response.body();
    }

    private JsonNode postJson(ModelRef model, String path, ObjectNode body) {
        byte[] raw = postBytes(model, path, body);
        return parseJson(raw);
    }

    private JsonNode parseJson(byte[] raw) {
        try {
            return JsonMappers.shared().readTree(raw);
        } catch (Exception e) {
            throw new CapabilityException(LlmErrorCodes.API_ERROR, "能力端点响应不是合法 JSON");
        }
    }

    /**
     * 连接级静默重试一次（与对话翻译器同构——响应头到达前的失败重发一次再报）
     */
    private HttpResponse<byte[]> sendWithOneRetry(HttpRequest request) {
        try {
            return holder.client().send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException first) {
            log.debug("能力端点连接级失败，静默重试一次（{}）", first.getClass().getSimpleName());
            try {
                return holder.client().send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException second) {
                throw new CapabilityException(LlmErrorCodes.NETWORK,
                        "连接失败（含重试一次后）：" + second.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CapabilityException(LlmErrorCodes.ABORTED, "发送被中断");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CapabilityException(LlmErrorCodes.ABORTED, "发送被中断");
        }
    }

    private void limitBytes(int length) {
        int max = systemParams.getInt(MAX_BYTES_PARAM, DEFAULT_MAX_BYTES);
        if (length > max) {
            throw new CapabilityException(LlmErrorCodes.API_ERROR,
                    "能力产物超过字节上限：" + length + " > " + max);
        }
    }
}
