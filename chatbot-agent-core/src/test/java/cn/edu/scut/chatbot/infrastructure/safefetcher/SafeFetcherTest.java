// 守护纪律：SafeFetcher 出站 SSRF 防线与 Content-Type 二进制拦截/文本与结构化文档放行边界（FR-019，T077）
package cn.edu.scut.chatbot.infrastructure.safefetcher;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.tool.ToolErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SafeFetcherTest {

    private static final String PUBLIC_IP_URL = "https://93.184.216.34/resource";

    private HttpClient httpClient;
    private SafeFetcher fetcher;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        fetcher = new SafeFetcher(httpClient, 1024L, Duration.ofSeconds(3));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> mockResponse(int status, Map<String, List<String>> headers, byte[] body) {
        HttpResponse<InputStream> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
        when(resp.body()).thenReturn(new ByteArrayInputStream(body));
        return resp;
    }

    @Test
    void 非https协议与私有回环IP目标被SSRF防线拦截且不发起出站请求() throws Exception {
        NonRetryableException httpErr = assertThrows(NonRetryableException.class,
                () -> fetcher.fetch("http://93.184.216.34/plain"));
        assertEquals(ToolErrorCodes.FETCH_BLOCKED, httpErr.getErrorCode());

        for (String privateUrl : List.of(
                "https://127.0.0.1/admin",
                "https://10.0.0.1/internal",
                "https://192.168.1.10/router",
                "https://169.254.169.254/latest/meta-data/")) {
            NonRetryableException ssrfErr = assertThrows(NonRetryableException.class,
                    () -> fetcher.fetch(privateUrl), "必须拦截私有地址：" + privateUrl);
            assertEquals(ToolErrorCodes.FETCH_BLOCKED, ssrfErr.getErrorCode());
            assertThrows(NonRetryableException.class, () -> SafeFetcher.validatePublicHttps(privateUrl));
        }

        assertDoesNotThrow(() -> SafeFetcher.validatePublicHttps(PUBLIC_IP_URL));
        verify(httpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void 二进制ContentType明确拦截防止拉取可执行或压缩包() throws Exception {
        for (String blockedType : List.of(
                "application/octet-stream",
                "application/zip",
                "application/x-msdownload",
                "image/png")) {
            HttpResponse<InputStream> resp = mockResponse(200,
                    Map.of("Content-Type", List.of(blockedType)),
                    "binary-payload".getBytes(StandardCharsets.UTF_8));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

            NonRetryableException ex = assertThrows(NonRetryableException.class,
                    () -> fetcher.fetch(PUBLIC_IP_URL), "必须拦截二进制类型：" + blockedType);
            assertEquals(ToolErrorCodes.FETCH_BLOCKED, ex.getErrorCode());
            assertTrue(ex.getMessage().contains(blockedType));
        }
    }

    @Test
    void 合法textHtml与applicationJson及结构化文档放行并返回正文() throws Exception {
        for (String allowedType : List.of(
                "text/html; charset=utf-8",
                "text/plain",
                "application/json; charset=UTF-8",
                "application/xml",
                "application/xhtml+xml",
                "application/problem+json")) {
            HttpResponse<InputStream> resp = mockResponse(200,
                    Map.of("Content-Type", List.of(allowedType)),
                    "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

            SafeFetcher.FetchedBody body = fetcher.fetch(PUBLIC_IP_URL);
            assertEquals("{\"ok\":true}", body.body());
            assertFalse(body.truncated());
        }

        // 缺失 Content-Type 头时兼容极简页面放行
        HttpResponse<InputStream> noHeader = mockResponse(200, Map.of(), "hello".getBytes(StandardCharsets.UTF_8));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(noHeader);
        assertEquals("hello", fetcher.fetch(PUBLIC_IP_URL).body());
    }

    @Test
    void 重定向首跳公网二跳内网与超跳数及缺Location均被拦截() throws Exception {
        HttpResponse<InputStream> redirectToPrivate = mockResponse(302,
                Map.of("Location", List.of("https://127.0.0.1/secret")), new byte[0]);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(redirectToPrivate);

        NonRetryableException secondHopErr = assertThrows(NonRetryableException.class,
                () -> fetcher.fetch(PUBLIC_IP_URL));
        assertEquals(ToolErrorCodes.FETCH_BLOCKED, secondHopErr.getErrorCode());
        verify(httpClient, times(1)).send(any(HttpRequest.class), any());

        // 缺少 Location 头
        HttpResponse<InputStream> missingLoc = mockResponse(302, Map.of(), new byte[0]);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(missingLoc);
        assertEquals(ToolErrorCodes.FETCH_BLOCKED,
                assertThrows(NonRetryableException.class, () -> fetcher.fetch(PUBLIC_IP_URL)).getErrorCode());

        // 超过 MAX_REDIRECTS=3 跳上限
        HttpResponse<InputStream> hopLoop = mockResponse(302,
                Map.of("Location", List.of("/next")), new byte[0]);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(hopLoop);
        NonRetryableException loopErr = assertThrows(NonRetryableException.class,
                () -> fetcher.fetch(PUBLIC_IP_URL));
        assertEquals(ToolErrorCodes.FETCH_BLOCKED, loopErr.getErrorCode());
        assertTrue(loopErr.getMessage().contains("3"));
    }

    @Test
    void 响应体超出maxResponseBytes截断标记与非2xx状态处理() throws Exception {
        SafeFetcher smallFetcher = new SafeFetcher(httpClient, 10L, Duration.ofSeconds(3));
        byte[] oversize = "12345678901234567890".getBytes(StandardCharsets.UTF_8);
        // 8192 buffer reads all 20 bytes in one read() (> 10), triggering truncation cleanly
        HttpResponse<InputStream> bigResp = mockResponse(200,
                Map.of("Content-Type", List.of("text/plain")), oversize);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(bigResp);

        SafeFetcher.FetchedBody truncatedBody = smallFetcher.fetch(PUBLIC_IP_URL);
        assertTrue(truncatedBody.truncated());

        HttpResponse<InputStream> notFound = mockResponse(404, Map.of(), new byte[0]);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(notFound);
        NonRetryableException statusErr = assertThrows(NonRetryableException.class,
                () -> smallFetcher.fetch(PUBLIC_IP_URL));
        assertEquals(ToolErrorCodes.FETCH_FAILED, statusErr.getErrorCode());
    }
}
