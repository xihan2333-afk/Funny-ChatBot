package cn.edu.scut.chatbot.access.transport;

import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OneBot 正向 WS 客户端（契约 onebot-ws-adapter.md 第 1 节）：JDK HttpClient WebSocket 封装。
 *
 * <p>职责：连接协议端（Universal 路径 /，Bearer 鉴权）、指数退避自动重连、双层心跳
 * （R4 决策）、鉴权失败判定与停连告警、连接就绪自检（get_version_info / get_login_info）、
 * API 调用的 echo 登记与响应路由（本类实现 {@link ApiResponseRouter}，US2 的 ApiClient 复用）。
 *
 * <p>入站帧的处理不在 WS 回调线程上做：组帧完成即刷新活跃时刻并转入有界队列，由独立的
 * 「ws-inbound-pipeline」虚拟线程串行消化——翻译与媒体转存耗时（单媒体 3 秒上限）不再阻塞
 * 帧接收，看门狗也不会因处理慢而误杀健康连接（恶意刷图打不断连接）。
 *
 * <p>并发形态：重连循环 / 心跳看门狗 / 协议层 ping / 入站管线各一条命名虚拟线程；
 * 阻塞等待全部 j.u.c。连接级状态（关闭信号）按连接实例化，杜绝旧连接迟到回调误杀新连接。
 *
 * <p>鉴权失败判定（R2）：第一判据是收到<b>无 echo 的</b> retcode=1403 响应帧（NapCat 推送
 * token 失败帧后断开；带 echo 的 1403 是对某次调用的业务失败回应，走投递失败回执，不判鉴权）；
 * 无失败帧而连续 N 次「连上即断」同样判定 token 错误——停止重连循环并告警
 * adapter.transport.auth_rejected，等待运维介入（无限重试 token 错误没有意义）。
 *
 * <p>「连上即断」的「连上」指<b>握手成功建立过的</b>连接：端口不可达、网络中断等
 * 从未建立成功的失败（ConnectException）是网络问题，不参与该判定——否则协议端晚于接入层
 * 启动的几十秒里就会误报 auth_rejected 永久停连，且表象完全误导排障方向。
 */
@Component
public class OneBotWsClient implements ApiResponseRouter, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OneBotWsClient.class);

    /**
     * 「连上即断」判定窗口：连接存活不足该毫秒数且未收到任何帧，记一次疑似鉴权失败
     */
    private static final long IMMEDIATE_DISCONNECT_WINDOW_MILLIS = 5000;

    /**
     * 入站处理队列上限：极端背压（协议端洪峰超过消化能力）时丢帧记 ERROR，优于把进程拖进假死循环
     */
    private static final int INBOUND_QUEUE_CAPACITY = 10_000;

    private final HttpClient httpClient;
    private final InboundFrameDispatcher dispatcher;
    private final ReactionCapability reactionCapability;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    private final String wsUrl;
    private final String accessToken;
    private final String expectedBotId;
    private final ReconnectBackoff backoff;
    private final long deathThresholdMultiplier;
    private final long fallbackHeartIntervalMillis;
    private final long protocolPingIntervalMillis;
    private final int authFailConnectAttempts;

    /**
     * echo 登记表：调用 → 等待响应的 future（连接断开时全部以 network 异常完成）
     */
    private final Map<String, CompletableFuture<JsonNode>> pendingCalls = new ConcurrentHashMap<>();
    private final AtomicLong echoSequence = new AtomicLong();

    /**
     * 入站帧处理队列（组帧完成即入队，管线线程串行消化，保留平台事件顺序）
     */
    private final BlockingQueue<String> inboundFrames = new LinkedBlockingQueue<>(INBOUND_QUEUE_CAPACITY);

    private volatile boolean running = false;
    private volatile boolean authRejected = false;
    private volatile boolean ready = false;
    private volatile WebSocket webSocket;
    /**
     * 当前连接的关闭信号（connectOnce 创建）：重连循环等它放行，判死 / ping 失败主动断开时也倒数它
     */
    private volatile CountDownLatch connectionClosedSignal;
    /**
     * 最近一次连接尝试是否成功完成过握手（join 返回才算）——「连上即断」判定只对
     * 握手成功后被断开的连接有意义；从未建立成功的失败是网络问题，不判鉴权
     */
    private volatile boolean connectionEstablished;
    private volatile long connectedAtMillis;
    private volatile boolean receivedAnyFrameInConnection;
    /**
     * 最近一次收到任何平台帧（组帧完成即刷新，不受处理耗时影响）的时刻——应用层心跳判死的基准
     */
    private volatile long lastActivityMillis;
    /**
     * 从心跳帧学到的协议端心跳间隔（未学前用 fallback）
     */
    private volatile long heartIntervalMillis;
    private int immediateDisconnectCount = 0;

    public OneBotWsClient(InboundFrameDispatcher dispatcher,
                          ReactionCapability reactionCapability,
                          Clock clock,
                          @Value("${access.napcat.ws-url}") String wsUrl,
                          @Value("${access.napcat.access-token:}") String accessToken,
                          @Value("${access.napcat.expected-bot-id:}") String expectedBotId,
                          @Value("${access.transport.reconnect.initial-backoff-millis:1000}") long initialBackoffMillis,
                          @Value("${access.transport.reconnect.backoff-multiplier:2}") double backoffMultiplier,
                          @Value("${access.transport.reconnect.max-backoff-millis:60000}") long maxBackoffMillis,
                          @Value("${access.transport.heartbeat.death-threshold-multiplier:3}") long deathThresholdMultiplier,
                          @Value("${access.transport.heartbeat.fallback-heart-interval-millis:30000}") long fallbackHeartIntervalMillis,
                          @Value("${access.transport.heartbeat.protocol-ping-interval-seconds:30}") long protocolPingIntervalSeconds,
                          @Value("${access.transport.auth-fail-connect-attempts:3}") int authFailConnectAttempts) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        this.dispatcher = dispatcher;
        this.reactionCapability = reactionCapability;
        this.clock = clock;
        this.wsUrl = wsUrl;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.expectedBotId = expectedBotId == null ? "" : expectedBotId;
        this.backoff = new ReconnectBackoff(initialBackoffMillis, backoffMultiplier, maxBackoffMillis);
        this.deathThresholdMultiplier = deathThresholdMultiplier;
        this.fallbackHeartIntervalMillis = fallbackHeartIntervalMillis;
        this.heartIntervalMillis = fallbackHeartIntervalMillis;
        this.protocolPingIntervalMillis = protocolPingIntervalSeconds * 1000;
        this.authFailConnectAttempts = authFailConnectAttempts;
    }

    // ===== SmartLifecycle：随 Spring 上下文启停 =====

    @Override
    public void start() {
        running = true;
        lastActivityMillis = clock.millis();
        Thread.ofVirtual().name("ws-reconnect-loop").start(this::reconnectLoop);
        Thread.ofVirtual().name("ws-app-heartbeat-watchdog").start(this::heartbeatWatchdogLoop);
        Thread.ofVirtual().name("ws-protocol-pinger").start(this::protocolPingLoop);
        Thread.ofVirtual().name("ws-inbound-pipeline").start(this::inboundPipelineLoop);
    }

    @Override
    public void stop() {
        running = false;
        ready = false;
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.abort();
        }
        failAllPendingCalls(new IllegalStateException("接入层正在停止"));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ===== 连接与重连 =====

    private void reconnectLoop() {
        while (running && !authRejected) {
            // 关闭信号按连接实例化：旧连接的迟到回调只能倒数自己的信号，不会误杀新连接
            CountDownLatch connectionClosed = new CountDownLatch(1);
            try {
                connectOnce(connectionClosed);
                // 等待本连接结束（正常关闭 / 错误 / 看门狗主动断开）
                connectionClosed.await();
            } catch (Exception e) {
                log.info("与协议端建立连接失败，按退避重试：{}", e.getMessage());
            }
            if (!running || authRejected) {
                break;
            }
            evaluateImmediateDisconnect();
            long delay = backoff.nextDelayMillis();
            log.info("协议端连接断开，{}ms 后第 {} 次重连", delay, backoff.consecutiveFailures());
            sleepQuietly(delay);
        }
        if (authRejected) {
            log.error("协议端鉴权失败，重连循环已停止，等待运维介入。errorCode={}",
                    ErrorCodes.ADAPTER_TRANSPORT_AUTH_REJECTED);
        }
    }

    private void connectOnce(CountDownLatch connectionClosed) {
        receivedAnyFrameInConnection = false;
        connectionEstablished = false;
        ready = false;

        WebSocket.Builder builder = httpClient.newWebSocketBuilder();
        if (!accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        WebSocket socket = builder
                .buildAsync(URI.create(wsUrl), new FrameListener(connectionClosed))
                .join();
        // 握手成功才算「连上」：之后被断才可能是协议端因 token 主动踢；
        // join 在此之前抛出的连接失败（端口不可达等）不参与「连上即断」判定
        connectionEstablished = true;
        connectedAtMillis = clock.millis();
        webSocket = socket;
        connectionClosedSignal = connectionClosed;
        lastActivityMillis = clock.millis();
        log.info("已连接协议端 {}，等待 lifecycle.connect 就绪信号", wsUrl);
    }

    /**
     * 主动断开当前连接并放行重连循环。JDK WebSocket 的 {@code abort()} 不保证触发 Listener
     * 的关闭回调（连接僵死时回调可能永远不来），而重连循环等待的正是这个信号——必须显式
     * 倒数，否则判死 / ping 失败后循环永久挂起（现象：静默时长持续增长、再无任何重连）。
     * 包私有：单测直接验证「abort 之外必然放行信号」这一关键行为。
     */
    void forceDisconnect(WebSocket socket) {
        ready = false;
        if (socket != null) {
            socket.abort();
        }
        CountDownLatch signal = connectionClosedSignal;
        if (signal != null) {
            signal.countDown();
        }
    }

    /**
     * 「连上即断」判定：握手成功建立过的连接，在窗口内未收到任何帧即断开，连续 N 次 = token 错误。
     * 从未建立成功的连接失败（connectionEstablished=false）不参与——那是网络问题，交给退避重连
     */
    private synchronized void evaluateImmediateDisconnect() {
        boolean immediate = connectionEstablished
                && !receivedAnyFrameInConnection
                && clock.millis() - connectedAtMillis < IMMEDIATE_DISCONNECT_WINDOW_MILLIS;
        if (immediate) {
            immediateDisconnectCount++;
            log.warn("连接建立后立即断开且未收到任何帧（第 {}/{} 次），疑似鉴权失败",
                    immediateDisconnectCount, authFailConnectAttempts);
            if (immediateDisconnectCount >= authFailConnectAttempts) {
                authRejected = true;
            }
        } else {
            immediateDisconnectCount = 0;
        }
    }

    /**
     * 收到无 echo 的 retcode=1403 帧：NapCat 的 token 错误第一判据（推失败帧再断开）
     */
    private void handleAuthRejectedFrame() {
        authRejected = true;
        log.error("协议端推送 retcode=1403 鉴权失败帧，停止重连循环，等待运维介入。errorCode={}",
                ErrorCodes.ADAPTER_TRANSPORT_AUTH_REJECTED);
        forceDisconnect(webSocket);
    }

    // ===== 双层心跳（R4 决策） =====

    /**
     * 应用层看门狗：超过 3 × heartInterval 未收到任何平台帧（心跳或业务事件都刷新活跃时刻，
     * 且刷新发生在组帧完成时、不受处理耗时影响）判定假死，主动断开触发重连。
     * NapCat 典型故障形态是「WS 还在、QQ 会话已掉线、事件流静默」，TCP 层 ping 对此无感——
     * 应用层心跳必须为主导。
     */
    private void heartbeatWatchdogLoop() {
        while (running) {
            sleepQuietly(Math.min(5000, Math.max(1000, heartIntervalMillis)));
            if (!running || webSocket == null) {
                continue;
            }
            long thresholdMillis = deathThresholdMultiplier * heartIntervalMillis;
            long silenceMillis = clock.millis() - lastActivityMillis;
            if (silenceMillis > thresholdMillis) {
                log.error("应用层心跳超时（静默 {}ms 超过阈值 {}ms），判定连接假死并主动断开重连。errorCode={}",
                        silenceMillis, thresholdMillis, ErrorCodes.ADAPTER_TRANSPORT_STALLED);
                forceDisconnect(webSocket);
            }
        }
    }

    /**
     * 协议层 ping：命名虚拟线程定时发 WS ping 帧作死链补充探活，发送失败即断开重连
     */
    private void protocolPingLoop() {
        while (running) {
            sleepQuietly(protocolPingIntervalMillis);
            WebSocket socket = webSocket;
            if (socket == null || !running) {
                continue;
            }
            try {
                socket.sendPing(ByteBuffer.allocate(0)).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("协议层 ping 发送失败，断开连接触发重连。errorCode={}",
                        ErrorCodes.ADAPTER_TRANSPORT_STALLED);
                forceDisconnect(socket);
            }
        }
    }

    // ===== 入站管线（独立虚拟线程，不占 WS 回调线程） =====

    private void inboundPipelineLoop() {
        while (running || !inboundFrames.isEmpty()) {
            String frame;
            try {
                frame = inboundFrames.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frame != null) {
                handleFrame(frame);
            }
        }
    }

    // ===== 帧处理 =====

    /**
     * 每连接一个监听器实例，持有本连接自己的关闭信号
     */
    private final class FrameListener implements WebSocket.Listener {

        private final CountDownLatch connectionClosed;
        private final StringBuilder textBuffer = new StringBuilder();

        private FrameListener(CountDownLatch connectionClosed) {
            this.connectionClosed = connectionClosed;
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String frame = textBuffer.toString();
                textBuffer.setLength(0);
                onFrameArrived(frame);
            }
            // 大帧请求续借缓冲：JDK WebSocket 要求监听器返回时数据已消费完
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("协议端连接关闭：statusCode={}，reason={}", statusCode, reason);
            connectionClosed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("协议端连接错误：{}", error.getMessage());
            connectionClosed.countDown();
        }
    }

    /**
     * 组帧完成即调用：刷新活跃时刻（看门狗基准不受处理耗时影响）并把帧转入处理队列
     */
    private void onFrameArrived(String frame) {
        receivedAnyFrameInConnection = true;
        lastActivityMillis = clock.millis();
        if (!inboundFrames.offer(frame)) {
            log.error("入站处理队列已满（{} 帧），背压丢帧——协议端洪峰超过消化能力。errorCode={}",
                    INBOUND_QUEUE_CAPACITY, ErrorCodes.ADAPTER_TRANSPORT_STALLED);
        }
    }

    /**
     * 单帧完整处理（ws-inbound-pipeline 线程）：鉴权失败帧识别 → 心跳 / 就绪语义 → 三路分发
     */
    private void handleFrame(String frame) {
        JsonNode node;
        try {
            node = json.readTree(frame);
        } catch (Exception e) {
            dispatcher.dispatch(frame);
            return;
        }

        // NapCat 鉴权失败帧（retcode=1403 且无 echo——带 echo 的是业务调用失败，走投递回执）：
        // 先判定停连，再走常规分发留档
        if (node.path("retcode").asInt(-1) == 1403 && !node.hasNonNull("echo")) {
            handleAuthRejectedFrame();
        }

        // meta_event 在 Transport 消化（不进信封），连接语义在此处理
        if ("meta_event".equals(node.path("post_type").asText())) {
            String metaEventType = node.path("meta_event_type").asText();
            if ("heartbeat".equals(metaEventType)) {
                JsonNode interval = node.get("interval");
                if (interval != null && interval.asLong() > 0) {
                    heartIntervalMillis = interval.asLong();
                }
                if (!node.path("status").path("online").asBoolean(true)) {
                    log.warn("协议端心跳上报 status.online=false（QQ 会话掉线信号，业务处置由 bot_offline 事件承载）");
                }
                return; // 心跳不进事件管线也不留档（无业务语义的高频帧）
            }
            if ("lifecycle".equals(metaEventType) && "connect".equals(node.path("sub_type").asText())) {
                onConnectionReady();
                return; // lifecycle 是连接信号，不是业务事件
            }
        }

        dispatcher.dispatch(frame);
    }

    /**
     * 连接就绪：退避重置 + 能力探测标记清除（新连接可能是升级后的实现）+ 自检
     */
    private void onConnectionReady() {
        ready = true;
        backoff.reset();
        reactionCapability.reset();
        log.info("收到 lifecycle.connect，协议端连接就绪");
        call("get_version_info", "{}").thenAccept(response -> {
            if (response.path("retcode").asInt(-1) == 0) {
                log.info("协议端自检：app_name={}，app_version={}，protocol_version={}",
                        response.path("data").path("app_name").asText("?"),
                        response.path("data").path("app_version").asText("?"),
                        response.path("data").path("protocol_version").asText("?"));
            }
        });
        call("get_login_info", "{}").thenAccept(response -> {
            String loginBotId = response.path("data").path("user_id").asText("");
            if (!expectedBotId.isBlank() && !expectedBotId.equals(loginBotId)) {
                log.error("协议端登录账号与配置不一致：登录={}，期望={}。errorCode={}",
                        loginBotId, expectedBotId, ErrorCodes.ADAPTER_TRANSPORT_BOT_ID_MISMATCH);
            }
        });
    }

    // ===== API 调用与响应路由 =====

    /**
     * 发起一次 API 调用：自增唯一 echo 登记 {@link #pendingCalls}，响应到达即 complete。
     * 连接不在时 future 以异常完成（就绪门控与静默重试由 OneBotApiClient 补齐）。
     */
    public CompletableFuture<JsonNode> call(String action, String paramsJson) {
        WebSocket socket = webSocket;
        if (socket == null || !running) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("协议端连接未建立：" + action));
        }
        String echo = "echo-" + echoSequence.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingCalls.put(echo, future);
        String requestFrame = "{\"action\":\"" + action + "\",\"params\":" + paramsJson
                + ",\"echo\":\"" + echo + "\"}";
        socket.sendText(requestFrame, true).whenComplete((ignored, error) -> {
            if (error != null) {
                pendingCalls.remove(echo);
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /**
     * ApiResponseRouter 实现：echo 命中登记表 → complete 对应等待方
     */
    @Override
    public boolean route(JsonNode responseFrame) {
        String echo = responseFrame.path("echo").asText(null);
        if (echo == null) {
            return false;
        }
        CompletableFuture<JsonNode> waiting = pendingCalls.remove(echo);
        if (waiting == null) {
            return false;
        }
        waiting.complete(responseFrame);
        return true;
    }

    /**
     * 连接断开时全部待匹配调用以 network 异常完成（重连后由 pending 重投机制重新执行，不缓存重放）
     */
    private void failAllPendingCalls(Throwable cause) {
        for (Map.Entry<String, CompletableFuture<JsonNode>> entry : pendingCalls.entrySet()) {
            if (pendingCalls.remove(entry.getKey()) != null) {
                entry.getValue().completeExceptionally(cause);
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 连接是否已就绪（收到 lifecycle.connect）
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 是否已判定鉴权失败并停止重连（等待运维介入）——就绪门控等待必须感知它，否则永久死等
     */
    public boolean isAuthRejected() {
        return authRejected;
    }
}
