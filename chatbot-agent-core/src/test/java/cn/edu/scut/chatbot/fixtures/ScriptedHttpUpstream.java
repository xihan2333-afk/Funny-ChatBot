package cn.edu.scut.chatbot.fixtures;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 脚本化 HTTP 假上游（research R10：JDK 内建 HttpServer 自建，依赖矩阵零新增）：
 * 按路径注册剧本、逐条回放——SSE 分片 flush 写出（流式增量语义）、延迟调度
 * （慢流/挂起刺激）、直接断连（流中断刺激）、整段错误体（八码刺激源）。
 *
 * <p>观察面：请求捕获（方法/路径/头/体——方言组装的断言依据）与命中计数
 * （「连接级重试一次」断言收到的请求数 = 2）。报文片段资源在
 * {@code src/test/resources/llm-fixtures/}（取自五家供应商公开文档示例——
 * 验证的是翻译与纪律，不是模型效果；真模型不进确定性测试，宪法第七条）。
 */
public class ScriptedHttpUpstream implements AutoCloseable {

    /**
     * 剧本步骤
     */
    public sealed interface Step permits Respond, SseChunks, Delay, Hang, DropConnection {
    }

    /**
     * 整段响应（错误体、非 SSE JSON 等一次性报文）
     */
    public record Respond(int status, String contentType, String body) implements Step {
    }

    /**
     * SSE 分片写出：每个 chunk 一次 write + flush（流式增量语义——翻译器逐事件消费的依据）
     */
    public record SseChunks(List<String> chunks) implements Step {
    }

    /**
     * 挂起指定毫秒（慢流刺激；可与 SseChunks 穿插——chunk 间延迟模拟 TTFT）
     */
    public record Delay(long millis) implements Step {
    }

    /**
     * 挂起到服务器关闭（读流超时刺激——连接活着但永远没有数据）
     */
    public record Hang() implements Step {
    }

    /**
     * 直接断开连接（流中断刺激——客户端读到意外 EOF）
     */
    public record DropConnection() implements Step {
    }

    /**
     * 捕获的请求（方言组装断言的观察面）——rawQuery 保留查询串（alt=sse 等断言依据）
     */
    public record RecordedRequest(String method, String path, String rawQuery,
                                  Map<String, List<String>> headers, String body) {
    }

    private final HttpServer server;

    private final Map<String, java.util.Queue<List<Step>>> scriptsByPath = new ConcurrentHashMap<>();

    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private final AtomicInteger totalHits = new AtomicInteger();

    /**
     * Hang 步骤的放行闩：close() 时打开，挂起的 handler 线程退出
     */
    private final CountDownLatch hangLatch = new CountDownLatch(1);

    public ScriptedHttpUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(runnable -> Thread.ofVirtual().name("fake-upstream-conn").start(runnable));
        server.createContext("/", this::handle);
    }

    /**
     * 按路径注册一个剧本（一次 HTTP 命中内按顺序回放全部步骤）；同一路径多次注册
     * 按注册顺序排队——第 N 次命中回放第 N 个剧本（重试场景的逐次变形）
     */
    public ScriptedHttpUpstream addScript(String path, List<Step> steps) {
        scriptsByPath.computeIfAbsent(path, key ->
                new java.util.concurrent.ConcurrentLinkedQueue<>()).add(List.copyOf(steps));
        return this;
    }

    public ScriptedHttpUpstream addScript(String path, Step... steps) {
        return addScript(path, List.of(steps));
    }

    /**
     * 启动监听（幂等——剧本先行注册与延迟启动两种用法都允许重复调用）
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        server.start();
    }

    private volatile boolean started;

    /**
     * 假上游根地址（provider_config.base_url 指向这里）
     */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 全部捕获请求（时序即到达序）
     */
    public List<RecordedRequest> recordedRequests() {
        return List.copyOf(requests);
    }

    /**
     * 指定路径的捕获请求
     */
    public List<RecordedRequest> requestsFor(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    /**
     * 总命中数（「连接级重试一次」断言口径：重试后失败场景应命中 2 次）
     */
    public int totalHits() {
        return totalHits.get();
    }

    @Override
    public void close() {
        hangLatch.countDown();
        server.stop(0);
    }

    /**
     * 连接拒绝端点：accept 后立即断开（客户端响应头未到达——连接级失败刺激源）。
     * accepts 计数是「连接级静默重试一次」的断言依据（重试后失败应 accept 到 2 次）。
     */
    public static RefusingEndpoint openRefusingEndpoint() {
        return new RefusingEndpoint();
    }

    /**
     * 连接拒绝端点（accept 即断）——用后 close 释放端口
     */
    public static final class RefusingEndpoint implements AutoCloseable {

        private final java.net.ServerSocket socket;

        private final AtomicInteger accepts = new AtomicInteger();

        private final Thread acceptLoop;

        private volatile boolean running = true;

        private RefusingEndpoint() {
            try {
                socket = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new IllegalStateException("连接拒绝端点启动失败", e);
            }
            acceptLoop = Thread.ofVirtual().name("refusing-endpoint").start(() -> {
                while (running) {
                    try {
                        var accepted = socket.accept();
                        accepts.incrementAndGet();
                        accepted.close();
                    } catch (IOException e) {
                        // close 后退出循环
                        return;
                    }
                }
            });
        }

        /**
         * 根地址（baseUrl 指向这里——连接建立后立即被断）
         */
        public String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        public int acceptCount() {
            return accepts.get();
        }

        @Override
        public void close() throws IOException {
            running = false;
            socket.close();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        totalHits.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(exchange.getRequestMethod(), path, query,
                exchange.getRequestHeaders(), body));
        try {
            playScript(exchange, path);
        } finally {
            exchange.close();
        }
    }

    private void playScript(HttpExchange exchange, String path) throws IOException {
        java.util.Queue<List<Step>> scripts = scriptsByPath.get(path);
        List<Step> script = scripts == null ? null : scripts.poll();
        if (script == null) {
            respond(exchange, 500, "text/plain", "no script for path: " + path);
            return;
        }
        OutputStream out = null;
        try {
            for (Step step : script) {
                switch (step) {
                    case Respond respond -> {
                        respond(exchange, respond.status(), respond.contentType(), respond.body());
                        return;
                    }
                    case Delay delay -> sleepQuietly(delay.millis());
                    case SseChunks sse -> {
                        if (out == null) {
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            out = exchange.getResponseBody();
                        }
                        for (String chunk : sse.chunks()) {
                            out.write(chunk.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                    case Hang unused -> {
                        // 连接活着但不发数据也不结束——读流侧只能等超时兜底
                        if (out == null) {
                            exchange.sendResponseHeaders(200, 0);
                            out = exchange.getResponseBody();
                        }
                        awaitHangRelease();
                    }
                    case DropConnection unused -> {
                        // 已发的数据保留，连接直接断（客户端读到意外 EOF——流中断刺激）
                        return;
                    }
                    default -> throw new IllegalStateException("未知剧本步骤：" + step);
                }
            }
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void awaitHangRelease() {
        try {
            hangLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
