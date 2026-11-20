package cn.edu.scut.chatbot.access.mediastore;

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 出站抓取统一底座（FR-019 纪律全量）：媒体 URL 属不可信输入——平台报文里任何字段都可能是
 * 攻击者构造的（宪法第六条「数据不是指令」的出口侧配套）。
 *
 * <p>抓取纪律五条：
 * <ol>
 *   <li>仅允许 https（http 明文在传输层即拒）</li>
 *   <li>私有网段 / 环回 / 链路本地地址拒绝（防 SSRF 内网探测）</li>
 *   <li>响应大小上限（累计字节超限即断，防超大响应耗尽内存与磁盘）</li>
 *   <li>连接与读取超时（防挂起连接占满媒体执行器）</li>
 *   <li>重定向逐跳重新走全部校验（自动跟随重定向会把校验短路掉，必须手动跟随）</li>
 * </ol>
 * Content-Type 白名单（image/ audio/ video/ 与通用二进制流）在响应头阶段校验。
 *
 * <p>任何违例抛 {@link NonRetryableException}（adapter.media.fetch_failed）——
 * 失败路径属正常业务分支（媒体转存失败不阻塞事件入流），不进重试体系。
 */
public class SafeFetcher {

    /**
     * 抓取成功的结果：媒体内容与其 Content-Type
     */
    public record FetchedMedia(String contentType, byte[] body) {
    }

    /**
     * 重定向手动跟随的最大跳数（防重定向环）
     */
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient httpClient;
    private final long maxDownloadBytes;
    private final Duration requestTimeout;

    public SafeFetcher(HttpClient httpClient, long maxDownloadBytes, Duration requestTimeout) {
        if (httpClient.followRedirects() != java.net.http.HttpClient.Redirect.NEVER) {
            // 自动跟随重定向会绕过逐跳校验（私有地址可借 302 跳入内网），必须手动跟随
            throw new IllegalArgumentException("SafeFetcher 的 HttpClient 必须配置 Redirect.NEVER");
        }
        this.httpClient = httpClient;
        this.maxDownloadBytes = maxDownloadBytes;
        this.requestTimeout = requestTimeout;
    }

    /**
     * 抓取一个媒体 URL，全部纪律校验通过才返回内容
     */
    public FetchedMedia fetch(String url) {
        URI uri = requireValidUrl(url, 0);
        return fetchFollowingRedirects(uri, 0);
    }

    /**
     * URL 形态校验：https + 主机名解析后不在私有网段（重定向每一跳都重新走这里）
     */
    private URI requireValidUrl(String url, int hop) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw rejected("URL 无法解析（第 " + hop + " 跳）：" + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw rejected("仅允许 https 出站抓取，实际 scheme=" + uri.getScheme());
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw rejected("URL 缺少主机名：" + url);
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    throw rejected("目标解析到私有 / 环回地址，拒绝出站：" + address.getHostAddress());
                }
            }
        } catch (IOException e) {
            // DNS 解析失败：媒体 URL 本就不可信，解析不了直接拒
            throw rejected("主机名解析失败：" + host);
        }
        return uri;
    }

    private boolean isPrivateAddress(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                // IPv6 唯一本地地址（fc00::/7）与 IPv4 广播段也一并拒绝
                || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)
                || (address.getAddress().length == 4 && address.getAddress()[0] == 127);
    }

    private FetchedMedia fetchFollowingRedirects(URI uri, int hop) {
        if (hop > MAX_REDIRECTS) {
            throw rejected("重定向跳数超过上限 " + MAX_REDIRECTS);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            // redirectPolicy 由注入的 HttpClient 决定，构造时必须 NEVER（自动跟随会绕过逐跳校验）
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw rejected("出站请求失败（第 " + hop + " 跳，" + uri.getHost() + "）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw rejected("出站请求被中断（第 " + hop + " 跳）");
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            List<String> location = response.headers().allValues("location");
            if (location.isEmpty()) {
                throw rejected("重定向响应缺少 Location 头");
            }
            // 逐跳：Location 重新走全部校验（scheme、私有网段、超时、大小）
            URI next = requireValidUrl(location.get(0), hop + 1);
            return fetchFollowingRedirects(next, hop + 1);
        }
        if (status < 200 || status >= 300) {
            throw rejected("出站响应非成功状态码：" + status);
        }
        String contentType = response.headers().firstValue("content-type").orElse("application/octet-stream");
        if (!isAllowedContentType(contentType)) {
            throw rejected("Content-Type 不在媒体白名单：" + contentType);
        }
        return new FetchedMedia(contentType, readBodyWithLimit(response, uri));
    }

    private boolean isAllowedContentType(String contentType) {
        String normalized = contentType.toLowerCase();
        return normalized.startsWith("image/")
                || normalized.startsWith("audio/")
                || normalized.startsWith("video/")
                || normalized.startsWith("application/octet-stream");
    }

    /**
     * 流式读取响应体，累计超过大小上限立即拒绝；缓冲按需扩容（不按上限预分配，防大额误配打爆内存）
     */
    private byte[] readBodyWithLimit(HttpResponse<InputStream> response, URI uri) {
        try (InputStream body = response.body()) {
            java.io.ByteArrayOutputStream accumulated = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (accumulated.size() + read > maxDownloadBytes) {
                    throw rejected("响应超过大小上限 " + maxDownloadBytes + " 字节：" + uri.getHost());
                }
                accumulated.write(buffer, 0, read);
            }
            return accumulated.toByteArray();
        } catch (IOException e) {
            throw rejected("响应体读取失败：" + e.getMessage());
        }
    }

    private NonRetryableException rejected(String reason) {
        return new NonRetryableException(ErrorCodes.ADAPTER_MEDIA_FETCH_FAILED, reason);
    }
}
