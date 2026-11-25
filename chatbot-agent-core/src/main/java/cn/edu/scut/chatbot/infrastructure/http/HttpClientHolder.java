package cn.edu.scut.chatbot.infrastructure.http;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 出站 HTTP 共享底座（设计方案 3.2.6「出站行为一个组件说了算」的 LLM 侧落点）：
 * 全系统共享一个 JDK HttpClient 实例，模型方言翻译器与能力型接口共用。
 *
 * <p>不走 SafeFetcher：SafeFetcher 的 SSRF 防线（私网拒绝、仅 https）面向不可信 URL
 * （链接预取、泛化工具的模板 URL）；模型 baseUrl 是运营配置的可信数据，且「内网 LLM
 * 网关」是合法部署形态，套 SSRF 拒绝会挡住合法场景（research R5）。
 *
 * <p>超时分层：连接超时固定 10 秒（HttpClient 构建期）；读超时是请求级参数
 * （HttpRequest.Builder.timeout），默认 300 秒兜底、可经 system_param
 * {@code llm.translator.read_timeout_seconds} 热调整；流停滞的精细判定（N 秒无增量）
 * 由循环的卡死检测承担，两者不冲突。
 */
@Component
public class HttpClientHolder {

    /**
     * 连接超时（秒）——HttpClient 不可变，构建期定死
     */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /**
     * 读超时兜底（秒）——防连接彻底僵死的最坏情况
     */
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 300;

    private final SystemParamService systemParamService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    @Autowired
    public HttpClientHolder(SystemParamService systemParamService) {
        this.systemParamService = systemParamService;
    }

    /**
     * 共享 HttpClient 实例（连接池与线程资源全系统一份）
     */
    public HttpClient client() {
        return httpClient;
    }

    /**
     * 当前读超时（秒）：system_param 有值用表值（热变更生效），缺省 300
     */
    public long readTimeoutSeconds() {
        return systemParamService.getLong("llm.translator.read_timeout_seconds",
                DEFAULT_READ_TIMEOUT_SECONDS);
    }

    /**
     * 当前读超时的 Duration 形态（HttpRequest.Builder.timeout 直取）
     */
    public Duration readTimeout() {
        return Duration.ofSeconds(readTimeoutSeconds());
    }
}
