package cn.edu.scut.chatbot.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Elasticsearch 客户端装配（设计方案 3.2.4：RestClient.builder 构建、注册为 bean；
 * 凭证不出服务端——浏览器不直连 ES，查询经管理接口转发）。
 *
 * <p>传输层说明：elasticsearch-java 8.11.4 只提供 RestClientTransport（8.13 才引入
 * 基于 JDK HttpClient 的 ElasticsearchHttpClient），故按方案 3.2.4 的 RestClient.builder
 * 字面执行——3.2.6「出站统一 JDK HttpClient」的约束对象是自研出站（模型 / 工具 / 预取），
 * 第三方客户端自带传输层不在其列；其 Apache HttpComponents 依赖随 elasticsearch-java
 * 传递引入，未新增登记项。
 *
 * <p>不可达语义（FR-006）：客户端装配不探测连通（启动时 ES 缺席不阻断应用）；
 * 查询失败由查询服务统一翻译为 observe.query_unavailable 可重试错误——连接超时 3 秒、
 * 读超时 10 秒，保证「明确可重试」而不是挂死。
 */
@Configuration
public class EsClientFactory {

    /**
     * 连接超时（毫秒）——ES 缺席时快速失败进可重试错误，绝不挂死调用方
     */
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    /**
     * 读超时（毫秒）——聚合查询上限（慢聚合也不允许无限等待）
     */
    private static final int SOCKET_TIMEOUT_MILLIS = 10000;

    /**
     * 低层 REST 客户端（懒连接：首次请求才建连接，ES 后启动也能自动接上）。
     * destroyMethod=close 随应用优雅关闭。
     */
    @Bean(destroyMethod = "close")
    public RestClient esRestClient(@Value("${observe.es-url}") String url,
                                   @Value("${observe.es-username:}") String username,
                                   @Value("${observe.es-password:}") String password) {
        RestClientBuilder builder = RestClient.builder(HttpHost.create(url));
        builder.setRequestConfigCallback(config -> config
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSocketTimeout(SOCKET_TIMEOUT_MILLIS));
        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + (password == null ? "" : password);
            String encoded = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            Header authHeader = new BasicHeader("Authorization", "Basic " + encoded);
            builder.setDefaultHeaders(new Header[]{authHeader});
        }
        return builder.build();
    }

    /**
     * API 客户端：JSON 映射复用全局 ObjectMapper（JsonMappers 纪律——ObjectMapper 全进程一份）
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient esRestClient, ObjectMapper sharedObjectMapper) {
        return new ElasticsearchClient(
                new RestClientTransport(esRestClient, new JacksonJsonpMapper(sharedObjectMapper)));
    }
}
