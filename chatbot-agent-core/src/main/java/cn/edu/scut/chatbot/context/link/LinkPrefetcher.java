package cn.edu.scut.chatbot.context.link;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.infrastructure.safefetcher.SafeFetcher;
import cn.edu.scut.chatbot.loop.message.LinkCardEntry;
import cn.edu.scut.chatbot.security.gatepolicy.GatePolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 群内 URL 复现计数（groupKey|url → 出现次数——二级摘要被动升级的触发信号：
     * 复现是最强也最便宜的"成为话题"信号）
     */
    private final Map<String, Integer> urlSightings = new ConcurrentHashMap<>();

    /**
     * 已升级二级摘要的（groupKey|url）——窗口内同链接只投一条摘要行，不再重复升级
     */
    private final java.util.Set<String> upgraded = ConcurrentHashMap.newKeySet();

    /**
     * 正文抽取缓存：normalized → 首段前 300 字（二级摘要的素材——质量不足时才有用）
     */
    private final Map<String, String> bodyExcerpts = new ConcurrentHashMap<>();

    /**
     * 二级摘要的目标字数（设计 :1243"二三百字"取中值）
     */
    private static final int SUMMARY_LEVEL_CHARS = 300;

    /**
     * 一级卡片质量判定阈值：摘要短于该字数视为"没给出像样的摘要"
     */
    private static final int CARD_QUALITY_MIN_CHARS = 30;

    private final SafeFetcher safeFetcher;
    private final SessionManager sessionManager;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final int cardSummaryChars;

    /**
     * 定界 + 注入检测组合件（US7 链接卡片通道——卡片字段是结构化存储不定界，
     * 检测挂在预取源头；定界发生在投影渲染处）。
     * setter 注入：未装配（隔离单测上下文）时零检测。
     */
    private cn.edu.scut.chatbot.security.delimit.DelimitGuard delimitGuard;
    private LinkCardMapper linkCardMapper;

    @Autowired(required = false)
    void setDelimitGuard(cn.edu.scut.chatbot.security.delimit.DelimitGuard guard) {
        this.delimitGuard = guard;
    }

    @Autowired(required = false)
    void setLinkCardMapper(LinkCardMapper linkCardMapper) {
        this.linkCardMapper = linkCardMapper;
    }

    /**
     * 安全闸策略面（US8 域名黑名单——请求发出前判定，命中不进入 SafeFetcher 按既定降级跳过）
     */
    private GatePolicyService gatePolicyService;

    @Autowired(required = false)
    void setGatePolicyService(GatePolicyService service) {
        this.gatePolicyService = service;
        if (service != null) {
            this.safeFetcher.setDomainBlocker(service::isDomainBlocked);
        }
    }

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
     * 从消息文本中提取新 URL 并预取为卡片条目追加转录尾部（快照纪律——不打断进行中 Run）。
     * 二级摘要被动升级：卡片质量不足且该 URL 在本群第二次出现时，升级为 300 字摘要条目
     * （复现计数按群隔离——同链接在别的群热与本群无关）
     */
    public void prefetchAndAppend(AgentSessionDO session, String messageText, String sharerUserId) {
        if (messageText == null) {
            return;
        }
        Matcher matcher = URL_PATTERN.matcher(messageText);
        while (matcher.find()) {
            String url = normalize(matcher.group());
            LinkCardEntry card = fetchOrCache(url, sharerUserId);
            if (card == null) {
                continue;
            }
            sessionManager.appendTranscript(session, java.util.List.of(card), null);
            LinkCardEntry summary = maybeUpgradeToSummary(session, url, card, sharerUserId);
            if (summary != null) {
                sessionManager.appendTranscript(session, java.util.List.of(summary), null);
            }
        }
    }

    /**
     * 二级升级判定（设计 :1243 两条件同时满足，纯代码可判）：
     * 卡片质量分不足（三个数据源都没给出像样摘要）且本群第二次及以后出现。
     * 摘要为确定性正文抽取（前三百字保头）——summarize 小模型槽位归 2.3，
     * 此处保证机制与条目形态（剧本纪律：升级机制本身是被测对象，摘要质量归模型侧）
     */
    private LinkCardEntry maybeUpgradeToSummary(AgentSessionDO session, String normalizedUrl,
                                                LinkCardEntry card, String sharerUserId) {
        if (cardQualityGood(card)) {
            return null;   // 质量良好的卡片不升级：主流站点 og 摘要已够接话
        }
        String sightingKey = session.getPlatform() + ":" + session.getBotId() + ":"
                + session.getGroupId() + "|" + normalizedUrl;
        if (!upgraded.add(sightingKey)) {
            return null;   // 该群此链接已升级过：窗口内一条摘要行即可，重复追加只是噪声
        }
        int sightings = urlSightings.merge(sightingKey, 1, Integer::sum);
        if (sightings < 2) {
            return null;   // 首次出现：先给一级卡片，等复现信号
        }
        String excerpt = bodyExcerpts.get(normalizedUrl);
        if (excerpt == null || excerpt.isBlank()) {
            return null;   // 正文抽取无货（预取失败占位的降级形态）
        }
        return new LinkCardEntry(
                Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 35,
                normalizedUrl, normalizedUrl, sharerUserId, LinkCardEntry.LEVEL_SUMMARY,
                card.title() != null ? card.title() : normalizedUrl, card.siteName(),
                excerpt);
    }

    /**
     * 一级卡片质量分：标题与摘要都像样（摘要达阈值字数）
     */
    private boolean cardQualityGood(LinkCardEntry card) {
        return card.title() != null && card.summary() != null
                && card.summary().length() >= CARD_QUALITY_MIN_CHARS;
    }

    /**
     * 三源卡片按序：缓存命中 → 平台字段（本 feature 无平台卡片源——留挂点）→ og meta → 正文首段
     */
    private LinkCardEntry fetchOrCache(String normalizedUrl, String sharerUserId) {
        LinkCardEntry cached = cardCache.get(normalizedUrl);
        if (cached != null) {
            return cached;   // 缓存命中：同一 URL 不重复预取（卡片条目复用——追加转录由调用方决定）
        }
        if (linkCardMapper != null) {
            LinkCardDO dbCard = linkCardMapper.selectByUrlHash(sha256(normalizedUrl));
            if (dbCard != null && dbCard.getExpiresAt() != null
                    && dbCard.getExpiresAt().isAfter(java.time.LocalDateTime.now(clock))) {
                LinkCardEntry entry = new LinkCardEntry(
                        Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 35,
                        normalizedUrl, normalizedUrl, sharerUserId, LinkCardEntry.LEVEL_CARD,
                        dbCard.getTitle() != null ? dbCard.getTitle() : normalizedUrl,
                        dbCard.getSiteName(), dbCard.getSummary());
                cardCache.put(normalizedUrl, entry);
                return entry;
            }
        }
        if (gatePolicyService != null) {
            try {
                String host = URI.create(normalizedUrl).getHost();
                if (gatePolicyService.isDomainBlocked(host)) {
                    log.info("域名命中黑名单，按降级路径跳过预取 url={} host={}", normalizedUrl, host);
                    return null;
                }
            } catch (Exception ignored) {
                // 非法 URL 交由 SafeFetcher 拦截
            }
        }
        try {
            SafeFetcher.FetchedBody fetched = safeFetcher.fetch(normalizedUrl);
            String html = fetched.body();
            String title = firstGroup(OG_TITLE, html);
            String siteName = firstGroup(OG_SITE, html);
            String summary = firstParagraphOf(html, cardSummaryChars);
            // 预取源头顺手做注入检测（卡片字段定界在投影渲染处——这里只留痕）
            if (delimitGuard != null && title != null) {
                delimitGuard.detect(
                        title + (summary != null ? " " + summary : ""),
                        cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.WEB);
            }
            bodyExcerpts.put(normalizedUrl, firstParagraphOf(html, SUMMARY_LEVEL_CHARS));
            LinkCardEntry card = new LinkCardEntry(
                    Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 35,
                    normalizedUrl, normalizedUrl, sharerUserId, LinkCardEntry.LEVEL_CARD,
                    title != null ? title : normalizedUrl, siteName, summary);
            cardCache.put(normalizedUrl, card);
            if (linkCardMapper != null) {
                try {
                    LinkCardDO row = new LinkCardDO();
                    row.setNormalizedUrl(normalizedUrl);
                    row.setUrlHash(sha256(normalizedUrl));
                    String host = URI.create(normalizedUrl).getHost();
                    row.setDomain(host != null ? host : "");
                    row.setTitle(card.title());
                    row.setSiteName(card.siteName());
                    row.setSummary(card.summary());
                    row.setSource("og_meta");
                    row.setExpiresAt(java.time.LocalDateTime.now(clock).plusDays(30));
                    linkCardMapper.upsert(row);
                } catch (Exception ignored) {
                }
            }
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

    public cn.edu.scut.chatbot.management.PageResult<LinkCardView> listLinkCards(
            String domain, String fromIso, String toIso, int page, int pageSize) {
        if (linkCardMapper == null) {
            return cn.edu.scut.chatbot.management.PageResult.of(java.util.List.of(), 0, page, pageSize);
        }
        int p = cn.edu.scut.chatbot.management.PageResult.normalizePage(page);
        int ps = cn.edu.scut.chatbot.management.PageResult.normalizePageSize(pageSize);
        java.time.LocalDateTime from = parseIso(fromIso);
        java.time.LocalDateTime to = parseIso(toIso);
        long total = linkCardMapper.countPaged(domain, from, to);
        java.util.List<LinkCardView> items = linkCardMapper
                .selectPaged(domain, from, to, cn.edu.scut.chatbot.management.PageResult.offset(p, ps), ps).stream()
                .map(c -> new LinkCardView(
                        c.getId(), c.getNormalizedUrl(), c.getDomain(), c.getTitle(),
                        c.getSiteName(), c.getSummary(), c.getSource(),
                        gatePolicyService != null && gatePolicyService.isDomainBlocked(c.getDomain()),
                        c.getExpiresAt() != null ? c.getExpiresAt().toString() : null,
                        c.getCreatedAt() != null ? c.getCreatedAt().toString() : null))
                .toList();
        return cn.edu.scut.chatbot.management.PageResult.of(items, total, p, ps);
    }

    public void deleteLinkCard(long id) {
        if (linkCardMapper == null) {
            return;
        }
        LinkCardDO existing = linkCardMapper.selectById(id);
        if (existing == null) {
            throw new cn.edu.scut.chatbot.exception.ChatBotException("mgmt.not_found", "链接卡片不存在 id=" + id);
        }
        linkCardMapper.deleteById(id);
        cardCache.remove(existing.getNormalizedUrl());
        bodyExcerpts.remove(existing.getNormalizedUrl());
    }

    private static java.time.LocalDateTime parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public record LinkCardView(Long id, String normalizedUrl, String domain, String title,
                               String siteName, String summary, String source, boolean domainBlocked,
                               String expiresAt, String createdAt) {
    }
}
