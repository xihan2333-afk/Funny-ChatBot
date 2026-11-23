package cn.edu.scut.chatbot.context.link;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.context.window.TokenEstimator;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.infrastructure.safefetcher.SafeFetcher;
import cn.edu.scut.chatbot.loop.message.LinkCardEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接预取管线（T095）：新 URL 纯代码预取（超时 3 秒、SSRF 全防线）→ 三源卡片
 * （平台卡片字段 → og meta → 正文首段按序取）→ 卡片条目追加转录尾部（不打断进行中 Run
 * ——快照纪律：追加只影响下个 Run）。全局 URL 规范化缓存（30 天 TTL 第一版以进程内缓存承载）。
 */
@Component
public class LinkPrefetcher {

    private static final Logger log = LoggerFactory.getLogger(LinkPrefetcher.class);

    private static final Pattern OG_TITLE = Pattern.compile(
            "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_SITE = Pattern.compile(
            "<meta[^>]+property=[\"']og:site_name[\"'][^>]+content=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_STRIP = Pattern.compile("<[^>]+>");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w./?%#&=+-]+");

    /**
     * URL 规范化缓存：normalized → 卡片（30 天进程内承载；跨进程缓存归 2.4 演进）
     */
    private final Map<String, LinkCardEntry> cardCache = new ConcurrentHashMap<>();

    private final SafeFetcher safeFetcher;
    private final SessionManager sessionManager;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final int cardSummaryChars;

    public LinkPrefetcher(SessionManager sessionManager,
                          IdGenerator idGenerator,
                          Clock clock,
                          cn.edu.scut.chatbot.bootstrap.SystemParamService systemParamService) {
        this.sessionManager = sessionManager;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.cardSummaryChars = systemParamService.getInt("context.card_summary_chars", 80);
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(
                        systemParamService.getLong("context.link_prefetch_timeout_millis", 3000)))
                .build();
        this.safeFetcher = new SafeFetcher(httpClient,
                systemParamService.getLong("context.link_max_response_bytes", 262144),
                Duration.ofMillis(systemParamService.getLong("context.link_prefetch_timeout_millis", 3000)));
    }

    /**
     * 从消息文本中提取新 URL 并预取为卡片条目追加转录尾部（快照纪律——不打断进行中 Run）
     */
    public void prefetchAndAppend(AgentSessionDO session, String messageText, String sharerUserId) {
        if (messageText == null) {
            return;
        }
        Matcher matcher = URL_PATTERN.matcher(messageText);
        while (matcher.find()) {
            String url = normalize(matcher.group());
            LinkCardEntry card = fetchOrCache(url, sharerUserId);
            if (card != null) {
                sessionManager.appendTranscript(session, java.util.List.of(card), null);
            }
        }
    }

    /**
     * 三源卡片按序：缓存命中 → 平台字段（本 feature 无平台卡片源——留挂点）→ og meta → 正文首段
     */
    private LinkCardEntry fetchOrCache(String normalizedUrl, String sharerUserId) {
        LinkCardEntry cached = cardCache.get(normalizedUrl);
        if (cached != null) {
            return cached;   // 缓存命中：同一 URL 不重复预取（卡片条目复用——追加转录由调用方决定）
        }
        try {
            SafeFetcher.FetchedBody fetched = safeFetcher.fetch(normalizedUrl);
            String html = fetched.body();
            String title = firstGroup(OG_TITLE, html);
            String siteName = firstGroup(OG_SITE, html);
            String summary = firstParagraphOf(html, cardSummaryChars);
            LinkCardEntry card = new LinkCardEntry(
                    Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 35,
                    normalizedUrl, normalizedUrl, sharerUserId, LinkCardEntry.LEVEL_CARD,
                    title != null ? title : normalizedUrl, siteName, summary);
            cardCache.put(normalizedUrl, card);
            return card;
        } catch (Exception e) {
            // 预取失败不阻塞主流程（卡片是增强信息——失败安静跳过）
            log.debug("链接预取失败跳过 url={} 原因={}", normalizedUrl, e.getMessage());
            return null;
        }
    }

    /**
     * URL 规范化：去锚点、去尾部斜杠、小写主机（规范化缓存命中率的口径）
     */
    private String normalize(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/$", "");
            return (uri.getScheme() + "://" + uri.getHost() + path
                    + (uri.getQuery() != null ? "?" + uri.getQuery() : "")).toLowerCase();
        } catch (Exception e) {
            return url;
        }
    }

    private String firstGroup(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String firstParagraphOf(String html, int maxChars) {
        String textOnly = html.replaceAll("(?is)<(script|style)[^>]*>.*?</(script|style)>", " ");
        String stripped = TAG_STRIP.matcher(textOnly).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return stripped.length() > maxChars ? stripped.substring(0, maxChars) : stripped;
    }
}
