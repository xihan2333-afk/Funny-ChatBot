package cn.edu.scut.chatbot.access.fixtures;

import org.apache.tomcat.websocket.server.WsSci;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.websocket.*;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 假 NapCat：内嵌 Tomcat 的 WebSocket 服务端（设计方案 4.1.2 测试基建假件）。
 *
 * <p>按剧本演的协议端替身（宪法第七条「模型与平台是假件、按剧本演」）：
 * <ul>
 *   <li>校验 Authorization Bearer 令牌；失败按 NapCat 真实行为先推 retcode=1403 失败帧再断开
 *       （research.md R2），也可配置「连上即断」的静默失败形态</li>
 *   <li>可编程推送任意文本帧（录制报文回放）、可编程自动心跳与停发心跳</li>
 *   <li>捕获全部入站 API 调用（action / params / echo）供断言</li>
 *   <li>按 echo 编排响应剧本、按 action 编排一次性剧本；未编排的调用按 action 给默认成功响应</li>
 * </ul>
 *
 * <p>WS 装配路径（踩坑收敛的结论）：借 Spring Boot 的内嵌 WebServer 承载
 * （{@code TomcatServletWebServerFactory}），在其初始化回调（等价于 war 部署中容器执行
 * ServletContainerInitializer 的阶段）做两件事：先补一个占位 servlet——Tomcat 的请求映射
 * 需要 context 里至少有一个 servlet 才会把请求送进过滤器链，裸 context（零 servlet）下请求
 * 在映射阶段就直接 404，WsFilter 永远接不到 WS 升级请求（这是此前握手恒 404 的根本原因；
 * war 部署由 conf/web.xml 的 default servlet 担任兜底，Boot 生产应用由 DispatcherServlet
 * 担任，纯 WS 假件两者皆无）；再手动执行 WsSci.onStartup，一次性装配 ServerContainer、
 * 注册 WS 升级过滤器 WsFilter 与会话 listener（已有容器时直接返回，幂等）。此前手工拼
 * Tomcat 的路全部走不通：{@code Tomcat.addContext} 挂的 FixContextListener 短路整个配置
 * 流程；手工 StandardContext 挂 ContextConfig 走 war 部署等价流程时 services 自动发现
 * 不生效；把 WsSci 挂给 Boot 的 contextCustomizer 它也不在链上生效。端点注册在容器就绪后
 * 动态添加（WsServerContainer 支持），鉴权经 modifyHandshake 捕获请求头。
 */
public final class FakeNapCat implements AutoCloseable {

    /**
     * 捕获到的一次入站 API 调用（rawFrame 为整帧原文，断言按内容包含判断）
     */
    public record CapturedApiCall(String action, String rawFrame, String echo) {
    }

    private final String accessToken;
    private final boolean silentAuthFailure;
    /**
     * 承载 WS 端点的内嵌 WebServer（Boot 容器），start() 时创建并启动
     */
    private WebServer webServer;
    /**
     * 自己选定的监听端口（借来的空闲端口），wsUrl() 直读此值——不依赖容器的端口查询语义
     */
    private int port;
    /**
     * 内嵌容器的 ServletContext，初始化回调时捕获，供端点动态注册用
     */
    private ServletContext servletContext;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<CapturedApiCall> capturedCalls = new CopyOnWriteArrayList<>();
    /**
     * echo → 回放响应剧本（完整响应 JSON，status/retcode/data/echo 均由剧本给出）
     */
    private final Map<String, String> responseScripts = new ConcurrentHashMap<>();
    /**
     * action → 一次性响应剧本队列（测试拿不到接入层自增的 echo 时，按 action 编排下一次响应）
     */
    private final Map<String, java.util.Deque<String>> actionScripts = new ConcurrentHashMap<>();
    /**
     * 每次 API 调用的可编程处理延迟（毫秒，默认 0）——同群串行 / 异群并行耗时断言用
     */
    private volatile long apiCallDelayMillis = 0;
    private final AtomicLong defaultMessageId = new AtomicLong(700_000);
    private ScheduledExecutorService heartbeatScheduler;
    private volatile long heartbeatSelfId;
    private volatile long heartbeatIntervalMillis;

    public FakeNapCat(String accessToken) {
        this(accessToken, false);
    }

    /**
     * @param accessToken       期望的 Bearer 令牌；空串表示不鉴权
     * @param silentAuthFailure true 时鉴权失败表现为「连上即断」（OneBot 标准形态），
     *                          false 时先推 1403 失败帧再断开（NapCat 形态）
     */
    public FakeNapCat(String accessToken, boolean silentAuthFailure) {
        this.accessToken = accessToken == null ? "" : accessToken;
        this.silentAuthFailure = silentAuthFailure;
    }

    /**
     * 启动 Boot 内嵌 WebServer（随机端口）承载 WS 容器，随后注册 Universal 端点。
     *
     * @throws DeploymentException   端点注册失败
     * @throws IllegalStateException WebServer 启动后 ServerContainer 未装配（WsSci 没有随容器执行）
     */
    public void start() throws Exception {
        // 端口选择：向内核借一个空闲端口（ServerSocket(0) 随机分配后立即归还）显式传给容器，
        // 并记入字段供 wsUrl() 直读——不依赖容器 getPort() 的查询语义（实测其在直用路径下
        // 可能返回未绑定标记 -1，或落到默认 8080）。借还之间存在极小竞态窗口，测试环境可接受。
        // 注意必须先关 portProbe 再 start：try 块内直接调 start 会让探针握着端口绑定，
        // Tomcat 随后绑同一端口必然「地址已在使用」。
        int borrowedPort;
        try (java.net.ServerSocket portProbe = new java.net.ServerSocket(0)) {
            borrowedPort = portProbe.getLocalPort();
        }
        start(borrowedPort);
    }

    /**
     * 在指定端口上启动（「先拒绝后可连」剧本用）：测试先让接入层连接一个无人监听的固定端口
     * （连接拒绝），再于同端口启动本假件接管——重连行为的测试需要端口在测试过程中从死到活。
     *
     * @throws DeploymentException   端点注册失败
     * @throws IllegalStateException WebServer 启动后 ServerContainer 未装配（WsSci 没有随容器执行）
     */
    public void start(int fixedPort) throws Exception {
        this.port = fixedPort;
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(port);
        // getWebServer 的返回值在 Boot 语义下已开始监听；其初始化回调等价于 war 部署中容器执行
        // ServletContainerInitializer 的阶段（动态注册 filter / listener 的合法窗口）。在此直接
        // 手动执行 WsSci.onStartup（war 部署中 WsSci 就是这么被容器调用的）：一次性装配
        // ServerContainer、注册 WS 升级过滤器 WsFilter 与会话 listener；已有容器时它直接返回，
        // 天然幂等。此前无论手工拼 Tomcat 还是把 WsSci 挂给 Boot 的 contextCustomizer，它都
        // 不在链上生效（握手恒 404），详见类注释。
        webServer = factory.getWebServer(servletContext -> {
            this.servletContext = servletContext;
            // 占位 servlet：Tomcat 的请求映射（Mapper）需要 context 里至少有一个 servlet 才会把
            // 请求送进过滤器链——裸 context（零 servlet）下请求在映射阶段就 404，WsFilter 永远
            // 没机会接手 WS 升级请求（本假件握手恒 404 的根本原因）。war 部署里这个兜底由
            // conf/web.xml 的 default servlet 担任，Boot 生产应用由 DispatcherServlet 担任。
            // 正常流量到不了它：升级请求被 WsFilter 截走，非升级请求返回 404 即可。
            servletContext.addServlet("placeholder", new javax.servlet.http.HttpServlet() {
                @Override
                protected void doGet(javax.servlet.http.HttpServletRequest request,
                                     javax.servlet.http.HttpServletResponse response)
                        throws java.io.IOException {
                    response.sendError(javax.servlet.http.HttpServletResponse.SC_NOT_FOUND);
                }
            }).addMapping("/");
            try {
                new WsSci().onStartup(null, servletContext);
            } catch (ServletException e) {
                throw new IllegalStateException("WsSci.onStartup 执行失败", e);
            }
        });
        // Boot 的 getWebServer 返回时 context 已启动但 connector 处于延迟绑定状态（未监听端口），
        // 必须显式 start() 完成端口绑定；已启动时此调用为空操作，幂等。
        webServer.start();

        // 容器就绪后动态注册端点（WsServerContainer 支持），鉴权经 modifyHandshake 捕获请求头
        ServerContainer container = (ServerContainer) servletContext
                .getAttribute(ServerContainer.class.getName());
        if (container == null) {
            throw new IllegalStateException(
                    "ServerContainer 未装配（WsSci.onStartup 未生效）——假 NapCat 无法提供 WS 服务");
        }
        ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder
                .create(NapCatEndpoint.class, "/")
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override
                    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request,
                                                HandshakeResponse response) {
                        String authorization = request.getHeaders()
                                .getOrDefault("Authorization", List.of()).stream()
                                .findFirst().orElse("");
                        config.getUserProperties().put("authorizationHeader", authorization);
                    }
                })
                .build();
        endpointConfig.getUserProperties().put("fakeNapCat", this);
        container.addEndpoint(endpointConfig);
    }

    /**
     * 接入层应连接的 WS 地址（Universal 路径：同一条连接既收事件也收 API 调用）
     */
    public URI wsUrl() {
        return URI.create("ws://localhost:" + port + "/");
    }

    // ===== 可编程行为 =====

    /**
     * 下行发送一律 basic 同步写并按 session 加锁：Tomcat 的 remote 非线程安全，onOpen 线程
     * （推 lifecycle 帧）与 onMessage 线程（回 API 响应）、测试线程（推报文）可能并发写同一
     * 连接，无锁时前一帧未写完即发起下一帧会抛「remote endpoint was in state
     * [TEXT_FULL_WRITING]」——帧静默丢失、回调线程被炸断连接（本假件实测丢帧根因）。
     */
    private void sendFrame(Session session, String text) {
        synchronized (session) {
            try {
                session.getBasicRemote().sendText(text);
            } catch (java.io.IOException | IllegalStateException e) {
                // 连接正在关闭等假件无排障价值的发送失败：记录后丢弃，不让异常炸断容器回调线程
                System.out.println("[fake-napcat] 下行帧发送失败并丢弃: " + e);
            }
        }
    }

    /**
     * 向全部连接推送任意文本帧（录制的 OneBot 报文回放）
     */
    public void pushTextFrame(String json) {
        for (Session session : sessions.values()) {
            sendFrame(session, json);
        }
    }

    /**
     * 开启自动心跳上报（NapCat 部署清单项：显式开启 + heartInterval 毫秒）
     */
    public synchronized void startHeartbeat(long selfId, long intervalMillis) {
        stopHeartbeat();
        heartbeatSelfId = selfId;
        heartbeatIntervalMillis = intervalMillis;
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("fake-napcat-heartbeat").factory());
        heartbeatScheduler.scheduleAtFixedRate(this::pushOneHeartbeat,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 停发心跳（模拟协议端事件流停摆——应用层心跳判死场景的剧本）
     */
    public synchronized void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    private void pushOneHeartbeat() {
        long now = System.currentTimeMillis() / 1000;
        pushTextFrame("{\"time\":" + now + ",\"self_id\":" + heartbeatSelfId
                + ",\"post_type\":\"meta_event\",\"meta_event_type\":\"heartbeat\","
                + "\"status\":{\"online\":true,\"good\":true},\"interval\":" + heartbeatIntervalMillis + "}");
    }

    /**
     * 主动断开全部连接（模拟协议端重启 / 网络中断）
     */
    public void closeAllConnections() {
        for (Session session : sessions.values()) {
            closeQuietly(session);
        }
    }

    /**
     * 推送 retcode=1403 鉴权失败帧后断开（NapCat 的 token 错误真实形态）
     */
    public void pushAuthRejectedAndClose() {
        for (Session session : sessions.values()) {
            sendFrame(session,
                    "{\"status\":\"failed\",\"retcode\":1403,\"data\":null,\"echo\":null,"
                            + "\"wording\":\"token验证失败\"}");
            closeQuietly(session);
        }
    }

    /**
     * 按 echo 编排响应剧本（完整响应 JSON 原样回放，echo 字段建议与键一致）
     */
    public void scriptResponse(String echo, String fullResponseJson) {
        responseScripts.put(echo, fullResponseJson);
    }

    /**
     * 按 action 编排一次性响应剧本：该 action 的下一次调用返回给定响应（原样回放），
     * 消费即失效。测试不知道接入层自增 echo 时用这个入口。
     */
    public void scriptNextResponseForAction(String action, String fullResponseJson) {
        actionScripts.computeIfAbsent(action, key -> new java.util.concurrent.ConcurrentLinkedDeque<>())
                .add(fullResponseJson);
    }

    /**
     * 设置每次 API 调用的处理延迟（同群串行 / 异群并行的耗时断言用）
     */
    public void setApiCallDelayMillis(long delayMillis) {
        this.apiCallDelayMillis = delayMillis;
    }

    /**
     * 已捕获的全部入站 API 调用（时序即调用序）
     */
    public List<CapturedApiCall> capturedCalls() {
        return List.copyOf(capturedCalls);
    }

    /**
     * 清空已捕获的调用（分场景断言互不污染）
     */
    public void clearCapturedCalls() {
        capturedCalls.clear();
    }

    /**
     * 当前开放连接数——重连场景的无副作用观察口（断开 / 重连可直接观测，不必推探测帧）
     */
    public int openConnectionCount() {
        return sessions.size();
    }

    // ===== 连接管理（endpoint 回调） =====

    private void registerSession(Session session, String authorizationHeader) {
        sessions.put(session.getId(), session);
        boolean authorized = accessToken.isEmpty()
                || ("Bearer " + accessToken).equals(authorizationHeader);
        if (authorized) {
            // 真实 NapCat 行为（OneBot 生命周期契约）：客户端连上并通过鉴权后，协议端立即推送
            // lifecycle.connect 生命周期事件——接入层以它为「连接就绪」信号开始自检与业务收发，
            // 没有它接入层会一直停在等待就绪、整个数据流不启动。
            sendFrame(session, "{\"time\":" + System.currentTimeMillis() / 1000
                    + ",\"self_id\":10000,\"post_type\":\"meta_event\","
                    + "\"meta_event_type\":\"lifecycle\",\"sub_type\":\"connect\"}");
        } else {
            if (silentAuthFailure) {
                closeQuietly(session);
            } else {
                sendFrame(session,
                        "{\"status\":\"failed\",\"retcode\":1403,\"data\":null,\"echo\":null,"
                                + "\"wording\":\"token验证失败\"}");
                closeQuietly(session);
            }
        }
    }

    private void unregisterSession(Session session) {
        sessions.remove(session.getId());
    }

    /**
     * 处理接入层发来的 API 调用：捕获整帧原文 → 可编程延迟 → 剧本回放或默认响应
     */
    private void onClientFrame(Session session, String text) {
        String action = extractJsonStringField(text, "action");
        String echo = extractJsonStringField(text, "echo");
        capturedCalls.add(new CapturedApiCall(action, text, echo));
        if (apiCallDelayMillis > 0) {
            try {
                Thread.sleep(apiCallDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(session, action, echo);
    }

    private void respond(Session session, String action, String echo) {
        String scripted = echo == null ? null : responseScripts.get(echo);
        if (scripted == null && action != null) {
            java.util.Deque<String> actionQueue = actionScripts.get(action);
            if (actionQueue != null) {
                scripted = actionQueue.poll();
            }
        }
        if (scripted != null) {
            // OneBot 协议：响应帧必须回带请求的 echo（接入层的响应路由键）。剧本未写 echo
            //（null）时用本次调用的真实 echo 填充——写 null 会造成响应永远路由不回调用方
            //（真实 NapCat 不会这样回帧，此前测试剧本踩坑点）
            if (echo != null && scripted.contains("\"echo\":null")) {
                scripted = scripted.replace("\"echo\":null", "\"echo\":\"" + echo + "\"");
            }
            sendFrame(session, scripted);
            return;
        }
        // 未编排的调用按 action 给默认响应：版本自检 / 登录自检 / 发消息，其余空 data 成功
        String data;
        switch (action == null ? "" : action) {
            case "get_version_info" -> data =
                    "{\"app_name\":\"FakeNapCat\",\"app_version\":\"1.0.0\",\"protocol_version\":\"v11\"}";
            case "get_login_info" -> data = "{\"user_id\":10000,\"nickname\":\"fake-bot\"}";
            case "send_group_msg", "send_private_msg", "send_msg" ->
                    data = "{\"message_id\":" + defaultMessageId.incrementAndGet() + "}";
            case "set_msg_emoji_like" -> data = "{\"result\":true}";
            default -> data = "{}";
        }
        sendFrame(session, "{\"status\":\"ok\",\"retcode\":0,\"data\":" + data
                + ",\"echo\":" + (echo == null ? "null" : "\"" + echo + "\"") + "}");
    }

    private String extractJsonStringField(String json, String field) {
        String needle = "\"" + field + "\"";
        int fieldIndex = json.indexOf(needle);
        if (fieldIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', fieldIndex + needle.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = json.indexOf('"', colonIndex);
        if (valueStart < 0) {
            return null;
        }
        int valueEnd = json.indexOf('"', valueStart + 1);
        return valueEnd > valueStart ? json.substring(valueStart + 1, valueEnd) : null;
    }

    private void closeQuietly(Session session) {
        try {
            session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "fake-napcat scripted close"));
        } catch (java.io.IOException ignored) {
            // 假件的剧本断开，关闭失败无排障价值
        }
    }

    @Override
    public synchronized void close() {
        stopHeartbeat();
        closeAllConnections();
        if (webServer != null) {
            webServer.stop();
        }
    }

    /**
     * JSR-356 编程式端点：把连接与入站帧桥接到 FakeNapCat 状态机
     */
    public static final class NapCatEndpoint extends Endpoint {

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            FakeNapCat fake = (FakeNapCat) config.getUserProperties().get("fakeNapCat");
            String authorizationHeader = String.valueOf(
                    config.getUserProperties().getOrDefault("authorizationHeader", ""));
            // 会话级 userProperties 持有引用，onClose 时可取回清理
            session.getUserProperties().put("fakeNapCat", fake);
            fake.registerSession(session, authorizationHeader);
            session.addMessageHandler(new MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    fake.onClientFrame(session, message);
                }
            });
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            FakeNapCat fake = (FakeNapCat) session.getUserProperties().get("fakeNapCat");
            if (fake != null) {
                fake.unregisterSession(session);
            }
        }
    }
}
