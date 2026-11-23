package cn.edu.scut.chatbot.infrastructure.safefetcher;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.tool.ToolErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 出站抓取统一底座（FR-019 纪律在 agent-core 侧的同构实现）：仅 https、私有网段拒绝
 * （解析目标 IP 判定——不是字符串前缀判断）、响应上限、超时、重定向逐跳校验
 * （HttpClient 手动重定向：每一跳重新走全部校验，防首跳公网二跳内网）。
 */
public class SafeFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeFetcher.class);

    /**
     * 重定向最大跟随跳数
     */
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient httpClient;

    private final long maxResponseBytes;

    private final Duration timeout;

    public SafeFetcher(HttpClient httpClient, long maxResponseBytes, Duration timeout) {
        this.httpClient = httpClient;
        this.maxResponseBytes = maxResponseBytes;
        this.timeout = timeout;
    }

    /**
     * 抓取结果：正文与是否被截断
     */
    public record FetchedBody(String body, boolean truncated) {
    }

    /**
     * 安全抓取：SSRF 全防线（私有网段拒绝、仅 https、重定向逐跳校验、响应上限、超时）。
     * 被纪律拦截抛 NonRetryableException（tool.fetch.blocked——环境隔离优于内容审查）。
     */
    public FetchedBody fetch(String url) {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            FetchTarget target = validateTarget(current);
            HttpResponse<java.io.InputStream> response = execute(target);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED,
                            "重定向响应缺少 Location 头（status=" + status + "）");
                }
                current = resolveRedirect(target.uri, location);
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new NonRetryableException(ToolErrorCodes.FETCH_FAILED,
                        "上游返回非成功状态 " + status);
            }
            return readBody(response);
        }
        throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED,
                "重定向超过 " + MAX_REDIRECTS + " 跳上限");
    }

    /**
     * 公共校验入口（HTTP 泛化工具执行器复用：同款 SSRF 防线判定，仅 https + 私有网段拒绝）
     */
    public static void validatePublicHttps(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED, "URL 不合法：" + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED,
                    "仅允许 https 出站（当前 scheme=" + uri.getScheme() + "）");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                    throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED,
                            "目标解析到私有/回环网段（" + address.getHostAddress() + "）——SSRF 防线拦截");
                }
            }
        } catch (NonRetryableException e) {
            throw e;
        } catch (Exception e) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_FAILED,
                    "目标域名解析失败：" + uri.getHost());
        }
    }

    private record FetchTarget(URI uri, HttpRequest request) {
    }

    /**
     * 目标校验：仅 https + 解析目标 IP 判私有网段（String 前缀判断可被 DNS 重绑定与十进制
     * IP 绕过——解析后按 InetAddress 判定）
     */
    private FetchTarget validateTarget(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED, "URL 不合法：" + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED,
                    "仅允许 https 出站（当前 scheme=" + uri.getScheme() + "）");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                    throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED,
                            "目标解析到私有/回环网段（" + address.getHostAddress() + "）——SSRF 防线拦截");
                }
            }
        } catch (NonRetryableException e) {
            throw e;
        } catch (Exception e) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_FAILED,
                    "目标域名解析失败：" + uri.getHost());
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "FunnyChatBot/1.0")
                .GET()
                .build();
        return new FetchTarget(uri, request);
    }

    private HttpResponse<java.io.InputStream> execute(FetchTarget target) {
        try {
            return httpClient.send(target.request(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_FAILED,
                    "出站请求失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NonRetryableException(ToolErrorCodes.FETCH_FAILED, "出站请求被中断", e);
        }
    }

    /**
     * 相对 Location 解析为绝对 URL（相对路径以当前跳为基准）
     */
    private String resolveRedirect(URI base, String location) {
        try {
            return base.resolve(location).toString();
        } catch (Exception e) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_BLOCKED,
                    "重定向地址不合法：" + location);
        }
    }

    private FetchedBody readBody(HttpResponse<java.io.InputStream> response) {
        try (java.io.InputStream body = response.body()) {
            byte[] buffer = new byte[8192];
            List<byte[]> chunks = new ArrayList<>();
            long total = 0;
            boolean truncated = false;
            int read;
            while ((read = body.read(buffer)) > 0) {
                total += read;
                if (total > maxResponseBytes) {
                    truncated = true;
                    break;
                }
                chunks.add(java.util.Arrays.copyOf(buffer, read));
            }
            StringBuilder text = new StringBuilder();
            for (byte[] chunk : chunks) {
                text.append(new String(chunk, java.nio.charset.StandardCharsets.UTF_8));
            }
            return new FetchedBody(text.toString(), truncated);
        } catch (IOException e) {
            throw new NonRetryableException(ToolErrorCodes.FETCH_FAILED,
                    "响应读取失败：" + e.getMessage(), e);
        }
    }
}
