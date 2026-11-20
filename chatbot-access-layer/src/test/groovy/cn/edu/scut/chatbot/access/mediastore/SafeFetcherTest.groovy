package cn.edu.scut.chatbot.access.mediastore

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException
import cn.edu.scut.chatbot.contract.error.ErrorCodes
import spock.lang.Specification

import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 守护纪律：SafeFetcher 出站抓取五条纪律（FR-019，宪法第六条出口侧配套）。
 * 媒体 URL 属不可信输入：SSRF 探测内网、超大响应耗尽资源、明文 http 降级，都要在出口挡住。
 * 单元测试 Mock HttpClient（宪法第七条 Mock 一切协作者），URL 判定与限额计算逐条验证。
 */
class SafeFetcherTest extends Specification {

    def httpClient = Mock(HttpClient)
    def fetcher

    def setup() {
        // SafeFetcher 构造纪律：HttpClient 必须 Redirect.NEVER（自动跟随会绕过逐跳校验），
        // stub 先行再构造（Spock 字段初始化早于 setup，构造只能放这里）
        httpClient.followRedirects() >> HttpClient.Redirect.NEVER
        fetcher = new SafeFetcher(httpClient, 1024, Duration.ofSeconds(3))
    }

    private HttpResponse<InputStream> responseOf(int status, String contentType, byte[] body,
                                                 Map<String, String> extraHeaders = [:]) {
        def response = Mock(HttpResponse)
        response.statusCode() >> status
        def headersMap = new LinkedHashMap<String, List<String>>()
        if (contentType != null) {
            headersMap["content-type"] = [contentType]
        }
        extraHeaders.each { key, value -> headersMap[key] = [value] }
        response.headers() >> HttpHeaders.of(headersMap, { a, b -> true })
        response.body() >> (body == null ? null : new ByteArrayInputStream(body))
        return response
    }

    def "http明文协议直接拒绝不出站"() {
        when:
        fetcher.fetch("http://multimedia.nt.qq.com.cn/img.jpg")

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == ErrorCodes.ADAPTER_MEDIA_FETCH_FAILED
        and: "请求根本没发出"
        0 * httpClient.send(_ as HttpRequest, _)
    }

    def "环回地址与私有网段全部拒绝"() {
        when:
        fetcher.fetch("https://127.0.0.1/x.jpg")

        then:
        thrown(NonRetryableException)
        0 * httpClient.send(_ as HttpRequest, _)

        when:
        fetcher.fetch("https://10.1.2.3/x.jpg")

        then:
        thrown(NonRetryableException)
        0 * httpClient.send(_ as HttpRequest, _)

        when:
        fetcher.fetch("https://192.168.1.92/x.jpg")

        then:
        thrown(NonRetryableException)
        0 * httpClient.send(_ as HttpRequest, _)
    }

    def "合法https且媒体白名单Content-Type放行"() {
        given:
        httpClient.send(_ as HttpRequest, _) >> responseOf(200, "image/jpeg", [0x01, 0x02] as byte[])

        when:
        def media = fetcher.fetch("https://multimedia.nt.qq.com.cn/img.jpg")

        then:
        media.contentType() == "image/jpeg"
        media.body().length == 2
    }

    def "响应超过大小上限拒绝"() {
        given: "上限 1024 字节，响应体 2048 字节"
        httpClient.send(_ as HttpRequest, _) >> responseOf(200, "image/png", new byte[2048])

        when:
        fetcher.fetch("https://multimedia.nt.qq.com.cn/big.png")

        then:
        thrown(NonRetryableException)
    }

    def "Content-Type非媒体白名单拒绝"() {
        given: "text/html 可能是攻击页面而非媒体"
        httpClient.send(_ as HttpRequest, _) >> responseOf(200, "text/html", "<html/>".bytes)

        when:
        fetcher.fetch("https://evil.example.com/x.jpg")

        then:
        thrown(NonRetryableException)
    }

    def "重定向逐跳重新校验且跳入私有地址被拒"() {
        given: "公网地址 302 跳到内网地址——自动跟随会绕过校验，手动逐跳必须拦下"
        httpClient.send(_ as HttpRequest, _) >> responseOf(302, null, null, [location: "https://192.168.1.92/evil.jpg"])

        when:
        fetcher.fetch("https://multimedia.nt.qq.com.cn/redirect.jpg")

        then:
        thrown(NonRetryableException)
    }

    def "重定向跳数超限拒绝"() {
        given: "每次都 302 到下一个公网地址，无限跳"
        httpClient.send(_ as HttpRequest, _) >> responseOf(302, null, null, [location: "https://multimedia.nt.qq.com.cn/loop.jpg"])

        when:
        fetcher.fetch("https://multimedia.nt.qq.com.cn/start.jpg")

        then:
        thrown(NonRetryableException)
    }

    def "HttpClient未配置NEVER重定向时构造即拒绝"() {
        given: "独立的 Mock，跟随策略为 NORMAL"
        def autoRedirectClient = Mock(HttpClient)
        autoRedirectClient.followRedirects() >> HttpClient.Redirect.NORMAL

        when:
        new SafeFetcher(autoRedirectClient, 1024, Duration.ofSeconds(3))

        then:
        thrown(IllegalArgumentException)
    }

    def "重定向Location跳到http明文即拒绝防止降级"() {
        given: "https 站点 302 到 http 地址——逐跳校验重新走 scheme 判定"
        httpClient.send(_ as HttpRequest, _) >> responseOf(302, null, null, [location: "http://8.8.8.8/downgrade.jpg"])

        when:
        fetcher.fetch("https://8.8.8.8/start.jpg")

        then:
        thrown(NonRetryableException)
    }

    def "重定向响应缺少Location头即拒绝"() {
        given:
        httpClient.send(_ as HttpRequest, _) >> responseOf(302, null, null)

        when:
        fetcher.fetch("https://8.8.8.8/start.jpg")

        then:
        thrown(NonRetryableException)
    }

    def "非成功非重定向状态码拒绝"() {
        when: "404"
        httpClient.send(_ as HttpRequest, _) >> responseOf(404, "text/plain", null)
        fetcher.fetch("https://8.8.8.8/missing.jpg")

        then:
        thrown(NonRetryableException)

        when: "500"
        httpClient.send(_ as HttpRequest, _) >> responseOf(500, "text/plain", null)
        fetcher.fetch("https://8.8.8.8/error.jpg")

        then:
        thrown(NonRetryableException)
    }

    def "响应体恰好等于大小上限放行"() {
        given: "上限 1024，响应体正好 1024 字节——边界取「超过才拒」"
        httpClient.send(_ as HttpRequest, _) >> responseOf(200, "image/png", new byte[1024])

        when:
        def media = fetcher.fetch("https://8.8.8.8/exact.png")

        then:
        media.body().length == 1024
    }

    def "IPv6环回与链路本地及站内保留段全部拒绝"() {
        when:
        fetcher.fetch("https://[::1]/x.jpg")

        then:
        thrown(NonRetryableException)
        0 * httpClient.send(_ as HttpRequest, _)

        when:
        fetcher.fetch("https://172.16.0.9/x.jpg")

        then:
        thrown(NonRetryableException)

        when:
        fetcher.fetch("https://169.254.1.1/x.jpg")

        then:
        thrown(NonRetryableException)

        when: "0.0.0.0（本机任意地址）"
        fetcher.fetch("https://0.0.0.0/x.jpg")

        then:
        thrown(NonRetryableException)
    }

    def "URL无法解析直接拒绝"() {
        when: "带空格的非法 URL"
        fetcher.fetch("https://8.8.8.8/bad url.jpg")

        then:
        thrown(NonRetryableException)
        0 * httpClient.send(_ as HttpRequest, _)
    }

    def "响应缺Content-Type头按通用二进制流放行"() {
        given: "QQ 的无 Content-Type 直链常见——默认 octet-stream 属白名单"
        httpClient.send(_ as HttpRequest, _) >> responseOf(200, null, [0x0A] as byte[])

        when:
        def media = fetcher.fetch("https://8.8.8.8/raw.bin")

        then:
        media.contentType() == "application/octet-stream"
        media.body() == [0x0A] as byte[]
    }

    def "Content-Type大小写不敏感放行"() {
        given: "协议端返回大写类型名（HTTP 头本就大小写不敏感）"
        httpClient.send(_ as HttpRequest, _) >> responseOf(200, "IMAGE/JPEG", [0x01] as byte[])

        when:
        def media = fetcher.fetch("https://8.8.8.8/upper.jpg")

        then:
        media.body().length == 1
    }
}
