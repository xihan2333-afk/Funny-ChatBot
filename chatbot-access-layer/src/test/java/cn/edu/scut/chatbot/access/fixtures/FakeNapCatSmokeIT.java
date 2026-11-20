package cn.edu.scut.chatbot.access.fixtures;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 假 NapCat 冒烟自检：内嵌 Tomcat WS 服务端与 JDK WebSocket 客户端的握手 / 收发联通性。
 * FakeNapCat 不依赖 Docker——集成测试握手失败时用本测试在本机快速定位（假件自身 vs 环境问题）。
 */
class FakeNapCatSmokeIT {

    @Test
    void jdkWebSocket客户端能与假NapCat握手并互收文本帧() throws Exception {
        try (FakeNapCat napcat = new FakeNapCat("smoke-token")) {
            napcat.start();

            // 诊断①：普通 HTTP GET / 的响应形态（区分 context 路由层 vs WS 升级层的问题）
            java.net.URI httpUrl = java.net.URI.create(napcat.wsUrl().toString().replaceFirst("^ws", "http"));
            java.net.http.HttpResponse<String> probe = java.net.http.HttpClient.newHttpClient()
                    .send(java.net.http.HttpRequest.newBuilder(httpUrl).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.println("[diag] HTTP GET / => " + probe.statusCode());
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/diag-http.txt"),
                    "HTTP=" + probe.statusCode() + " body=" + probe.body());

            StringBuilder received = new StringBuilder();
            CompletableFuture<WebSocket> connection = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header("Authorization", "Bearer smoke-token")
                    .buildAsync(URI.create(napcat.wsUrl().toString()), new WebSocket.Listener() {
                        @Override
                        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            synchronized (received) {
                                received.append(data);
                            }
                            // JDK WebSocket 的接收窗口：初始许可只有 1，每帧回调须显式补 1，
                            // 否则第一帧（假件连上即推的 lifecycle.connect）之后不再投递任何帧
                            webSocket.request(1);
                            return null;
                        }
                    });

            // 握手应在 10 秒内完成（失败会以异常完成——这正是要抓的）
            WebSocket webSocket = connection.get(10, TimeUnit.SECONDS);

            // 客户端 → 假协议端：API 调用帧应被捕获（发送完成不等于对端已处理，异步等待）
            webSocket.sendText("{\"action\":\"get_version_info\",\"params\":{},\"echo\":\"e1\"}", true)
                    .get(5, TimeUnit.SECONDS);
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> napcat.capturedCalls().stream().anyMatch(call -> "e1".equals(call.echo())));

            // 假协议端 → 客户端：响应帧应到达。假件连上即推 lifecycle.connect（就绪信号），
            // 不能以「收到首帧」为通过条件，须轮询到目标响应内容为止。
            // 读取与写入同锁——裸读没有 happens-before，可能永远看不到接收线程的追加
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(receivedText(received)).contains("\"echo\":\"e1\""));
        }
    }

    /**
     * 与接收回调同锁读取累积帧（内存可见性：写入锁 + 无锁读不构成 happens-before）
     */
    private static String receivedText(StringBuilder received) {
        synchronized (received) {
            return received.toString();
        }
    }
}
