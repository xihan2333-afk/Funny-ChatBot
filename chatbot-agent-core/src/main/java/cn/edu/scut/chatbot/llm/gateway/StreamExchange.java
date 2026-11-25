package cn.edu.scut.chatbot.llm.gateway;

import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder;
import cn.edu.scut.chatbot.llm.slot.LlmErrorCodes;
import cn.edu.scut.chatbot.llm.sse.SseParser;
import cn.edu.scut.chatbot.loop.CancellationSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * 翻译器五步骨架的共用底座（contracts/model-gateway.md 第 2 节「发送」与「解析 SSE」
 * 两步）：发送 HTTP 请求 → 按响应形态分流 → SSE 行流驱动回调。两方言翻译器与能力型
 * 接口客户端组合使用（各自只关心请求组装与事件解释）。
 *
 * <p>骨架纪律：
 * <ul>
 *   <li>连接级静默重试一次：响应头到达之前抛的连接类失败（连接拒绝/连接超时）重发一次，
 *       再失败才 fail-fast——这是翻译器内唯一重试（应用级错误直接进流，重试归上层）</li>
 *   <li>读超时兜底：请求级超时取 HttpClientHolder（默认 300 秒可调）；流停滞的精细判定
 *       是循环侧卡死检测的职责，这里只防响应头迟迟不到的最坏情况</li>
 *   <li>非 SSE 响应体按 Content-Type 识别分流：错误体整段读出交给调用方归一化，
 *       不进 SSE 解析（HTTP 200 但 JSON 错误形态同样走这条路）</li>
 *   <li>取消信号在连接前置位：不发起请求直接 onAborted；读流中置位由看门狗关闭流
 *       唤醒阻塞读（body.read 不可被 interrupt，close 是唯一出口——取消唯一通道纪律）</li>
 * </ul>
 *
 * <p>本组件不抛异常：一切失败经 {@link ExchangeHandler} 回调（错误码已归一化，
 * ERROR 级日志由调用方带 errorCode 与 traceId 落）。
 */
@Component
public class StreamExchange {

    private static final Logger log = LoggerFactory.getLogger(StreamExchange.class);

    /**
     * 交换结果回调：调用方（翻译器）据此组装统一事件
     */
    public interface ExchangeHandler {

        /**
         * 收到了完整 HTTP 响应但不是 SSE 流（错误体、非 SSE JSON——状态码含 200，
         * 按错误归一化处置归调用方）
         */
        void onHttpError(int status, String contentType, String body);

        /**
         * 一个完整 SSE 事件的 data（多行已拼接；[DONE] 哨兵也经此——识别归调用方）
         */
        void onSseData(String data);

        /**
         * SSE 流正常关闭（readLine 到 EOF——Gemini 阵营的终止方式）
         */
        void onStreamEnd();

        /**
         * 连接或读流失败（重试已用尽）：errorCode 为 llm.network / llm.timeout
         */
        void onIoFailure(String errorCode, String message);

        /**
         * 取消信号置位终止（连接前或读流中——已推增量保留在调用方）
         */
        void onAborted();
    }

    private final HttpClientHolder holder;

    private final SseParser sseParser = new SseParser();

    public StreamExchange(HttpClientHolder holder) {
        this.holder = holder;
    }

    /**
     * 请求级读超时暴露给调用方（翻译器组装 HttpRequest 时必须带上——
     * HttpRequest 无安全复制重建途径，读超时只能在构造期给定）
     */
    public java.time.Duration readTimeout() {
        return holder.readTimeout();
    }

    /**
     * 执行一次交换（同步阻塞——调用方在虚拟线程上驱动）。不抛异常。
     */
    public void exchange(HttpRequest request, ExchangeHandler handler, CancellationSignal signal) {
        if (signal.isCancelled()) {
            handler.onAborted();
            return;
        }
        HttpResponse<InputStream> response = sendWithOneRetry(request, handler);
        if (response == null) {
            return; // 失败已回调
        }
        int status = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.contains("text/event-stream")) {
            handler.onHttpError(status, contentType, readAllQuietly(response.body()));
            return;
        }
        consumeSse(response.body(), handler, signal);
    }

    /**
     * 发送并处理连接级静默重试一次。返回 null 表示失败已回调（调用方直接返回）。
     */
    private HttpResponse<InputStream> sendWithOneRetry(HttpRequest request, ExchangeHandler handler) {
        // 请求必须自带 timeout（调用方经 readTimeout() 取——HttpRequest 无安全的
        // 复制重建途径，newBuilder() 不保留原请求字段）
        if (request.timeout().isEmpty()) {
            log.warn("模型请求未设置读超时（调用方漏配——按无限等待执行，应修复）");
        }
        try {
            return holder.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException first) {
            // 连接级失败（响应头未到达）：静默重试一次——唯一翻译器内重试
            log.debug("模型请求连接级失败，静默重试一次（{}）", first.getClass().getSimpleName());
            try {
                return holder.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException second) {
                handler.onIoFailure(ioErrorCode(second),
                        "连接失败（含重试一次后）：" + second.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handler.onIoFailure(LlmErrorCodes.ABORTED, "发送被中断");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handler.onIoFailure(LlmErrorCodes.ABORTED, "发送被中断");
            return null;
        }
    }

    /**
     * 连接类 IO 异常的错误码归类：超时类 → llm.timeout，其余（含连接拒绝）→ llm.network
     */
    private static String ioErrorCode(IOException e) {
        return e instanceof java.net.http.HttpTimeoutException
                || e instanceof java.net.SocketTimeoutException
                ? LlmErrorCodes.TIMEOUT : LlmErrorCodes.NETWORK;
    }

    private void consumeSse(InputStream body, ExchangeHandler handler, CancellationSignal signal) {
        try (InputStream stream = body) {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            startCancelWatchdog(stream, signal, done);
            try {
                sseParser.parse(stream, handler::onSseData, signal);
                if (signal.isCancelled()) {
                    handler.onAborted();
                    return;
                }
                handler.onStreamEnd();
            } finally {
                // 流结束（正常或异常）——看门狗随之退出（正常完成的流不取消信号，
                // 没有 done 标志看门狗会永久轮询泄漏）
                done.countDown();
            }
        } catch (IOException e) {
            if (signal.isCancelled()) {
                handler.onAborted();
                return;
            }
            log.warn("模型 SSE 流中断 errorCode={}（{}）", LlmErrorCodes.NETWORK, e.getMessage());
            handler.onIoFailure(LlmErrorCodes.NETWORK, "流中断：" + e.getMessage());
        }
    }

    /**
     * 取消看门狗：signal 置位后关闭流，唤醒阻塞在 read 上的解析线程
     * （body.read 不可被 interrupt——close 是唯一出口）。流结束（done）时退出——
     * 正常完成的流不泄漏轮询线程。
     */
    private void startCancelWatchdog(InputStream stream, CancellationSignal signal,
                                     java.util.concurrent.CountDownLatch done) {
        Thread.ofVirtual().name("sse-cancel-watch").start(() -> {
            while (true) {
                if (signal.isCancelled()) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        // 流可能已被解析侧关闭——忽略
                    }
                    return;
                }
                if (done.getCount() == 0) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private String readAllQuietly(InputStream body) {
        if (body == null) {
            return "";
        }
        try (InputStream stream = body) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("错误体读取失败（按空体归一化）：{}", e.getMessage());
            return "";
        }
    }
}
