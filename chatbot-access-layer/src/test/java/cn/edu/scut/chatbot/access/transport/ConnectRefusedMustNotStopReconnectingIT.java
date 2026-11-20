package cn.edu.scut.chatbot.access.transport;

import cn.edu.scut.chatbot.access.fixtures.FakeNapCat;
import cn.edu.scut.chatbot.access.inbound.AccessLayerIntegrationTestBase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.time.Duration;

/**
 * 守护意图：协议端端口暂不可达（容器未起 / 端口未监听 / 网络中断）是<b>网络问题</b>，
 * 不是鉴权问题——「连上即断」判定（R2）的对象是「握手成功但未收到任何帧即被协议端断开」
 * 的连接（NapCat 的 token 错误形态），<b>从未建立成功</b>的连接（ConnectException）不得计入，
 * 否则协议端晚起几十秒，接入层就误报 auth_rejected 永久停连、要人工重启才能恢复。
 */
class ConnectRefusedMustNotStopReconnectingIT extends AccessLayerIntegrationTestBase {

    /**
     * 接入层要连的固定端口：测试前半段无人监听（连接拒绝），后半段 FakeNapCat 在同端口接管——
     * 端口在测试过程中「从死到活」，重连循环的自愈能力因此可直接观察
     */
    private static final int PORT = borrowFreePort();

    @Autowired
    private OneBotWsClient wsClient;

    static {
        // 专线剧本：接入层连这个固定端口（见基类 napcatWsUrlOverride 的说明——
        // 同 key 的 @DynamicPropertySource 覆盖不生效，必须走覆盖口；127.0.0.1 直连避开
        // localhost 的 IPv6 优先解析变数）
        napcatWsUrlOverride = "ws://127.0.0.1:" + PORT + "/";
    }

    /**
     * 退避压到 200ms 起步：3 秒观察窗内连接拒绝已远超判定阈值 3 次，误判若存在必已触发
     */
    @DynamicPropertySource
    static void unreachableNapcatProperties(DynamicPropertyRegistry registry) {
        registry.add("access.transport.reconnect.initial-backoff-millis", () -> "200");
    }

    @Test
    void 连接从未建立的失败不判鉴权且重连循环保持存活() throws Exception {
        // 前半段：无人监听，200ms 起步退避下 3 秒内连接拒绝远超 3 次；
        // 整个窗口内鉴权误判必须始终不出现（bug 形态：第 3 次失败后 authRejected 翻 true，
        // during 断言随失败败）
        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .until(() -> !wsClient.isAuthRejected());

        // 后半段：端口上出现真协议端，重连循环必须还活着并最终就绪——
        // 连接失败不许杀死自愈能力（停连的接入层对它无反应，atMost 超时败）
        FakeNapCat napcat = new FakeNapCat(NAPCAT_ACCESS_TOKEN);
        try {
            napcat.start(PORT);
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(wsClient::isReady);
        } finally {
            napcat.close();
        }
    }

    /**
     * 向内核借一个空闲端口后立即归还（借还存在极小竞态窗口，测试环境可接受）
     */
    private static int borrowFreePort() {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("借空闲端口失败", e);
        }
    }
}
