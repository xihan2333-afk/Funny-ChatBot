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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护意图：「连上即断」判定（R2）的本意——握手成功但未收到任何帧即被协议端断开，
 * 连续 3 次判定 token 错误，停止重连并等待运维介入（无限重试一个错误的 token 没有意义）。
 * 与 {@link ConnectRefusedMustNotStopReconnectingIT} 互补：那边守护「连不上不算」，这边守护「连上即断要算」。
 */
class SilentAuthRejectionMustStopReconnectingIT extends AccessLayerIntegrationTestBase {

    /**
     * 每次握手成功即被无帧踢掉的假协议端：令牌与接入层不匹配 + silentAuthFailure 形态
     * （OneBot 标准的静默鉴权失败，不推 1403 失败帧）
     */
    private static final FakeNapCat SILENT_REJECTING = new FakeNapCat("wrong-token", true);
    private static final int PORT = borrowFreePort();

    static {
        try {
            SILENT_REJECTING.start(PORT);
        } catch (Exception e) {
            throw new IllegalStateException("连上即断形态的假协议端启动失败", e);
        }
        // 专线剧本：接入层连本类假件（见基类 napcatWsUrlOverride 的说明——
        // 同 key 的 @DynamicPropertySource 覆盖不生效，必须走覆盖口；127.0.0.1 直连避开
        // localhost 的 IPv6 优先解析变数）。接入层令牌走基类默认的 NAPCAT_ACCESS_TOKEN，
        // 与假件的 wrong-token 不匹配：每次握手成功即被踢
        napcatWsUrlOverride = "ws://127.0.0.1:" + PORT + "/";
    }

    @Autowired
    private OneBotWsClient wsClient;

    @DynamicPropertySource
    static void silentRejectingNapcatProperties(DynamicPropertyRegistry registry) {
        registry.add("access.transport.reconnect.initial-backoff-millis", () -> "200");
    }

    @Test
    void 连上即断连续三次判定鉴权失败并停止重连() {
        // 200ms 起步退避，3 次连上即断约 1.4 秒内完成，15 秒上限足够宽裕
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(wsClient::isAuthRejected);

        // 停连的完整语义：连接从未就绪过（无 lifecycle.connect），不是「边重连边告警」
        assertThat(wsClient.isReady()).isFalse();
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
