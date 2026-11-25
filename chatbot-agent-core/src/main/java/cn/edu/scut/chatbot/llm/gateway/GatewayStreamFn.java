package cn.edu.scut.chatbot.llm.gateway;

import cn.edu.scut.chatbot.llm.slot.CredentialPool;
import cn.edu.scut.chatbot.llm.slot.LlmErrorCodes;
import cn.edu.scut.chatbot.loop.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关流式实现（loop/StreamFn 的生产实现——EngineConfiguration 的
 * unavailableStreamFn 桩被本 Bean 就位后自动退位）。
 *
 * <p>按 ModelRequest.model().dialect() 查 DialectRegistry 派发；未知 dialect 以
 * Failed(llm.config_missing) 终止；一切异常兜底转 Failed 绝不外抛（内核拿到的永远是
 * 流内消息而不是异常——宪法第五条的翻译器侧承诺在网关再兜一层底）。
 *
 * <p>凭证反馈联动（contracts/slot-and-credentials.md 第 3 节）：网关观察 Failed 事件，
 * llm.rate_limited 反馈冷却、llm.auth 反馈停用——错误码与反馈的联动内聚网关不散落
 * 调用方；翻译器不感知池（它只归一错误码推 Failed）。US4 装配空实现，US6 换 JDBC 池。
 */
@Component
public class GatewayStreamFn implements StreamFn, ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(GatewayStreamFn.class);

    private final DialectRegistry registry;

    private final CredentialPool credentialPool;

    private final cn.edu.scut.chatbot.llm.slot.SlotResolver slotResolver;

    private final cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder;

    public GatewayStreamFn(DialectRegistry registry, CredentialPool credentialPool,
                           cn.edu.scut.chatbot.llm.slot.SlotResolver slotResolver,
                           cn.edu.scut.chatbot.llm.slot.CostRecorder costRecorder) {
        this.registry = registry;
        this.credentialPool = credentialPool;
        this.slotResolver = slotResolver;
        this.costRecorder = costRecorder;
    }

    @Override
    public void stream(ModelRequest request, StreamConsumer consumer, CancellationSignal signal) {
        try {
            ModelRef model = request.model();
            if (model == null || !ModelRef.isKnownDialect(model.dialect())) {
                consumer.onEvent(failed(LlmErrorCodes.CONFIG_MISSING,
                        "模型方言缺失或未注册：" + (model == null ? "（ModelRef 为空）" : model.dialect())));
                return;
            }
            DialectTranslator translator = registry.byDialect(model.dialect());
            if (translator == null) {
                consumer.onEvent(failed(LlmErrorCodes.CONFIG_MISSING,
                        "方言注册表无此方言的翻译器：" + model.dialect()));
                return;
            }
            translator.translate(new TranslateContext(request,
                    withCredentialFeedback(consumer, request), signal));
        } catch (Exception e) {
            // 翻译器承诺不抛异常，这里是对抗性兜底（翻译器 bug 也不让异常穿透内核）
            log.error("翻译器执行异常（兜底转 Failed，绝不外抛） errorCode={} traceId=n/a",
                    LlmErrorCodes.API_ERROR, e);
            consumer.onEvent(failed(LlmErrorCodes.API_ERROR,
                    "翻译器内部异常：" + e.getMessage()));
        }
    }

    /**
     * callOnce 单次调用（contracts/model-gateway.md 第 1 节）：槽位解析 + 凭证解析 +
     * 复用翻译器五步与事件流（多模态装载与思考契约同享），外收集 Completed 返回文本
     * 与用量。槽位解析失败返回错误结果非异常（L1 场景由调用方保守 ignore）；
     * 调用方显式给的 params 优先，null 则用槽位 gen_params 默认。
     */
    @Override
    public CallOnceResult callOnce(String slotCode, LlmCallMeta meta,
                                   String systemPrompt, List<LlmMessage> messages,
                                   GenerationParams params) {
        cn.edu.scut.chatbot.loop.ModelConfig config;
        try {
            config = slotResolver.resolve(slotCode);
        } catch (cn.edu.scut.chatbot.llm.slot.ModelCatalog.SlotResolutionException e) {
            return CallOnceResult.failure(LlmErrorCodes.CONFIG_MISSING, e.getMessage());
        }
        GenerationParams effective = params != null ? params : config.params();
        String credential = credentialPool.nextAvailable(config.model().providerCode());
        if (credential == null) {
            return CallOnceResult.failure(LlmErrorCodes.CONFIG_MISSING,
                    "供应商无可用凭证 provider=" + config.model().providerCode());
        }
        long startNanos = System.nanoTime();
        long[] firstDeltaNanos = {-1};
        List<ModelStreamEvent> collected = new java.util.ArrayList<>();
        stream(new ModelRequest(config.model(), messages, systemPrompt, List.of(),
                        effective, credential),
                event -> {
                    if (firstDeltaNanos[0] < 0 && (event instanceof ModelStreamEvent.ThinkingDelta
                            || event instanceof ModelStreamEvent.TextDelta
                            || event instanceof ModelStreamEvent.ToolCallDelta)) {
                        firstDeltaNanos[0] = System.nanoTime();
                    }
                    collected.add(event);
                },
                CancellationSignal.create());
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        long ttftMs = firstDeltaNanos[0] < 0 ? 0 : (firstDeltaNanos[0] - startNanos) / 1_000_000;
        for (ModelStreamEvent event : collected) {
            if (event instanceof ModelStreamEvent.Completed completed) {
                costRecorder.record(meta, config.model(),
                        new cn.edu.scut.chatbot.llm.slot.CostSample(
                                completed.message().usage(), ttftMs, totalMs,
                                completed.message().stopReason(), null));
                return CallOnceResult.success(completed.message().textContent(),
                        completed.message().usage());
            }
            if (event instanceof ModelStreamEvent.Failed failed) {
                costRecorder.record(meta, config.model(),
                        new cn.edu.scut.chatbot.llm.slot.CostSample(null, ttftMs, totalMs,
                                failed.errorCode() != null && failed.errorCode().endsWith("aborted")
                                        ? "aborted" : "error",
                                failed.errorCode()));
                return CallOnceResult.failure(failed.errorCode(), failed.errorMessage());
            }
        }
        return CallOnceResult.failure(LlmErrorCodes.API_ERROR, "未收到终止事件（流被意外截断）");
    }

    /**
     * 包装事件出口观察 Failed 终止事件：限流/鉴权失败时反馈凭证池（掩码定位——
     * 明文不落日志）。反馈异常不吞错误事件本身（联动失败不阻塞主流程）。
     */
    private StreamConsumer withCredentialFeedback(StreamConsumer delegate, ModelRequest request) {
        return event -> {
            delegate.onEvent(event);
            if (event instanceof ModelStreamEvent.Failed failed) {
                feedbackOnFailure(failed.errorCode(), request);
            }
        };
    }

    private void feedbackOnFailure(String errorCode, ModelRequest request) {
        ModelRef model = request.model();
        String credential = request.resolvedCredential();
        if (model == null || credential == null || credential.isEmpty()) {
            return;
        }
        try {
            if (LlmErrorCodes.RATE_LIMITED.equals(errorCode)) {
                credentialPool.feedback(model.providerCode(),
                        CredentialPool.maskOf(credential), CredentialPool.FeedbackKind.RATE_LIMITED);
            } else if (LlmErrorCodes.AUTH.equals(errorCode)) {
                credentialPool.feedback(model.providerCode(),
                        CredentialPool.maskOf(credential), CredentialPool.FeedbackKind.AUTH_FAILED);
            }
        } catch (Exception e) {
            log.warn("凭证反馈异常（不阻塞错误进流） errorCode={}", errorCode, e);
        }
    }

    private static ModelStreamEvent.Failed failed(String errorCode, String message) {
        return new ModelStreamEvent.Failed(errorCode, message);
    }
}
