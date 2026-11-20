package cn.edu.scut.chatbot.access.transport;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * OneBot API 调用客户端（契约 onebot-ws-adapter.md 第 2/3 节）：包装 {@link OneBotWsClient}
 * 的原始调用，补齐投递侧语义——
 *
 * <ul>
 *   <li>就绪门控：连接就绪前的调用等待不失败（等待期间停机则以异常完成）</li>
 *   <li>连接级失败（连接断开、发送超时）静默重试一次（全局口径）；retcode 明确失败不重试
 *       （平台语义已拒绝，重试只会重复副作用）</li>
 * </ul>
 */
@Component
public class OneBotApiClient {

    /**
     * 平台 API 响应的结构化视图（raw 保留响应帧原文，供出方向留档审计）
     */
    public record OneBotResponse(String status, int retcode, JsonNode data, String echo, String wording,
                                 String raw) {

        /**
         * retcode=0 成功、=1 已提交异步（async）——两者都算调用成功
         */
        public boolean isOk() {
            return retcode == 0 || retcode == 1;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OneBotApiClient.class);

    private final OneBotWsClient wsClient;

    public OneBotApiClient(OneBotWsClient wsClient) {
        this.wsClient = wsClient;
    }

    /**
     * 发起一次 API 调用。返回的 future 完成即代表拿到平台响应帧（无论成败）；
     * 连接级失败自动静默重试一次（重连重建后重发）。
     */
    public CompletableFuture<OneBotResponse> call(String action, String paramsJson) {
        return callWithRetry(action, paramsJson, true);
    }

    private CompletableFuture<OneBotResponse> callWithRetry(String action, String paramsJson, boolean retryAllowed) {
        CompletableFuture<OneBotResponse> result = new CompletableFuture<>();
        awaitReady()
                .thenCompose(ignored -> wsClient.call(action, paramsJson))
                .whenComplete((frame, error) -> {
                    if (error == null) {
                        result.complete(toResponse(frame));
                        return;
                    }
                    if (retryAllowed && isConnectionLevelFailure(error)) {
                        // 连接级失败静默重试一次（重连重建后重发，无重复副作用风险）
                        log.info("API 调用连接级失败，静默重试一次：{}", action);
                        callWithRetry(action, paramsJson, false).whenComplete((retried, retriedError) -> {
                            if (retriedError == null) {
                                result.complete(retried);
                            } else {
                                result.completeExceptionally(retriedError);
                            }
                        });
                    } else {
                        // 平台明确失败（retcode 非 ok/async）不重试：响应经 result 交给调用方判定
                        result.completeExceptionally(asCompletion(error));
                    }
                });
        return result;
    }

    /**
     * 就绪门控：未就绪时在虚拟线程上轮询等待（就绪前的调用等待不失败）。
     * 停机或鉴权失败停连时以异常完成——鉴权失败是永久态，等待方必须拿到失败去产回执，
     * 否则群串行泵会永久阻塞（无回执、队列无界堆积）。
     */
    private CompletableFuture<Void> awaitReady() {
        if (wsClient.isReady()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> readyGate = new CompletableFuture<>();
        Thread.ofVirtual().name("api-call-ready-gate").start(() -> {
            while (!readyGate.isDone()) {
                if (!wsClient.isRunning()) {
                    readyGate.completeExceptionally(
                            new IllegalStateException("接入层正在停止，调用放弃"));
                    return;
                }
                if (wsClient.isAuthRejected()) {
                    readyGate.completeExceptionally(new IllegalStateException(
                            "协议端鉴权失败已停止重连（token 配置错误？），调用失败"));
                    return;
                }
                if (wsClient.isReady()) {
                    readyGate.complete(null);
                    return;
                }
                sleepQuietly(200);
            }
        });
        return readyGate;
    }

    private static OneBotResponse toResponse(JsonNode frame) {
        return new OneBotResponse(
                frame.path("status").asText(null),
                frame.path("retcode").asInt(-1),
                frame.path("data"),
                frame.path("echo").asText(null),
                frame.path("wording").asText(null),
                frame.toString());
    }

    /**
     * 连接级失败判据：底层调用未到达平台（连接断开 / 发送失败），重试无重复副作用风险
     */
    private static boolean isConnectionLevelFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause instanceof IllegalStateException
                || cause instanceof java.io.IOException
                || cause instanceof java.util.concurrent.TimeoutException;
    }

    private static CompletionException asCompletion(Throwable error) {
        return error instanceof CompletionException completion
                ? completion
                : new CompletionException(error);
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
