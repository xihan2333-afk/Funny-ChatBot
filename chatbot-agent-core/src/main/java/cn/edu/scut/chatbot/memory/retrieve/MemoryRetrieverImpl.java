package cn.edu.scut.chatbot.memory.retrieve;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.context.window.TokenEstimator;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantErrorCodes;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.SlotResolver;
import cn.edu.scut.chatbot.memory.MemoryErrorCodes;
import cn.edu.scut.chatbot.memory.entry.MemoryDecay;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.security.delimit.DelimitHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MemoryRetriever 实现（T009 交付派生三动作；search 六步管道 T023 生产化）。
 *
 * <p>双存储次序纪律：reembed / rebuild 全部「MySQL 已有事实 → 派生索引重写」——
 * 本类的写路径永远以 MySQL 行为事实源，Qdrant 只承接重算结果。
 */
@Component
public class MemoryRetrieverImpl implements MemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrieverImpl.class);

    /**
     * 重建灌入批量（embedding 批调用与 upsert 请求体的双上界）
     */
    static final int REBUILD_BATCH = 100;

    /**
     * 空召回的正常业务文本（非 error——没找到记忆是合法结果）
     */
    static final String NO_RESULT_TEXT = "没有找到相关记忆";

    private final MemoryEntryMapper entryMapper;
    private final QdrantClient qdrant;
    private final EmbeddingClient embeddingClient;
    private final SlotResolver slotResolver;
    private final SystemParamService systemParams;
    private final Clock clock;

    public MemoryRetrieverImpl(MemoryEntryMapper entryMapper,
                               QdrantClient qdrant,
                               EmbeddingClient embeddingClient,
                               SlotResolver slotResolver,
                               SystemParamService systemParams,
                               Clock clock) {
        this.entryMapper = entryMapper;
        this.qdrant = qdrant;
        this.embeddingClient = embeddingClient;
        this.slotResolver = slotResolver;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    /**
     * 定界 + 注入检测组合件（US7 七通道收口——记忆检索是通道之一）。
     * setter 注入：未装配（隔离单测上下文）时回退静态定界，只丢检测不丢定界。
     */
    private cn.edu.scut.chatbot.security.delimit.DelimitGuard delimitGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDelimitGuard(cn.edu.scut.chatbot.security.delimit.DelimitGuard guard) {
        this.delimitGuard = guard;
    }

    @Override
    public MemorySearchResult search(GroupKey group, MemorySearchQuery query) {
        RetrieveParams params = RetrieveParams.load(systemParams);
        java.time.Instant now = clock.instant();
        try {
            return doSearch(group, query, params, now);
        } catch (Exception e) {
            // 失败不抛异常（检索是模型主动调用的拉路径——isError 给模型看，不杀循环）
            log.warn("记忆检索失败 errorCode={}：{}", QdrantErrorCodes.QDRANT_UNAVAILABLE, e.getMessage());
            return new MemorySearchResult(true,
                    "记忆检索暂不可用（" + QdrantErrorCodes.QDRANT_UNAVAILABLE + "）");
        }
    }

    private MemorySearchResult doSearch(GroupKey group, MemorySearchQuery query,
                                        RetrieveParams params, java.time.Instant now) {
        // ① query 同步向量化（百毫秒级）
        float[] queryVector = embedBatch(List.of(query.queryText())).get(0);

        // ② Qdrant 召回——隔离三元组过滤强制注入（调用方不传也被补上，跨群隔离是结构保证）
        List<QdrantClient.QdrantFilter.KeywordCondition> keywords = new ArrayList<>(List.of(
                new QdrantClient.QdrantFilter.KeywordCondition("platform", group.platform()),
                new QdrantClient.QdrantFilter.KeywordCondition("botId", group.botId()),
                new QdrantClient.QdrantFilter.KeywordCondition("groupId", group.groupId())));
        if (query.memoryType() != null && !query.memoryType().isBlank()) {
            keywords.add(new QdrantClient.QdrantFilter.KeywordCondition("memoryType", query.memoryType()));
        }
        if (query.userId() != null && !query.userId().isBlank()) {
            keywords.add(new QdrantClient.QdrantFilter.KeywordCondition("userId", query.userId()));
        }
        List<QdrantClient.QdrantFilter.RangeCondition> ranges = List.of();
        if (query.withinDays() != null && query.withinDays() > 0) {
            long fromEpoch = now.minus(java.time.Duration.ofDays(query.withinDays())).toEpochMilli();
            ranges = List.of(new QdrantClient.QdrantFilter.RangeCondition("occurredAt", fromEpoch, null));
        }
        List<QdrantClient.QdrantHit> hits = qdrant.search(MemoryIndexPoints.COLLECTION_ALIAS,
                queryVector, new QdrantClient.QdrantFilter(keywords, ranges), params.oversample());
        if (hits.isEmpty()) {
            return new MemorySearchResult(false, NO_RESULT_TEXT);
        }

        // ③ 按点 id 现读 MySQL（正文 / strength / lastAccessedAt——易变字段不进 Qdrant）
        Map<Long, Double> similarityById = new HashMap<>();
        for (QdrantClient.QdrantHit hit : hits) {
            similarityById.put(hit.id(), hit.score());
        }
        List<MemoryEntryDO> entries = entryMapper.selectByIds(new ArrayList<>(similarityById.keySet()))
                .stream()
                // 归档/被取代条目的物理点可能仍在索引（归档退出检索不动物理点）——现读后过滤
                .filter(entry -> MemoryEntryDO.STATUS_ACTIVE.equals(entry.getStatus()))
                .toList();
        if (entries.isEmpty()) {
            return new MemorySearchResult(false, NO_RESULT_TEXT);
        }

        // ④ 应用层重排：score = cosine × decayFactor × (0.5 + strength/200)
        //    （Qdrant 只管召回，业务打分全在应用层；decayFactor 与写侧共用 MemoryDecay）
        MemoryDecay.DecayParams decayParams = MemoryDecay.load(systemParams);
        record Ranked(MemoryEntryDO entry, double score) {
        }
        List<Ranked> ranked = new ArrayList<>(entries.size());
        for (MemoryEntryDO entry : entries) {
            double decayFactor = MemoryDecay.decayFactor(entry.getStrength(),
                    occurredInstant(entry), lastAccessedInstant(entry), now, decayParams);
            double score = similarityById.getOrDefault(entry.getId(), 0.0)
                    * decayFactor * (0.5 + entry.getStrength() / 200.0);
            ranked.add(new Ranked(entry, score));
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));

        // ⑤ 截 topKMin~topKMax、按 tokenBudget 裁剪（单条正文先压到均摊预算——
        //    保底 topKMin 条时硬上限天然不破）
        int perLineBudget = Math.max(1, params.tokenBudget() / (params.topKMin() + 1));
        List<String> lines = new ArrayList<>();
        List<Long> reinforcedIds = new ArrayList<>();
        int usedTokens = 0;
        for (Ranked item : ranked) {
            if (lines.size() >= params.topKMax()) {
                break;
            }
            MemoryEntryDO entry = item.entry();
            String line = renderLine(entry, perLineBudget);
            int cost = TokenEstimator.estimate(line);
            if (usedTokens + cost > params.tokenBudget() && lines.size() >= params.topKMin()) {
                break;   // 预算耗尽（不足 topKMin 条时仍收——单条已被均摊预算压住）
            }
            lines.add(line);
            reinforcedIds.add(entry.getId());
            usedTokens += cost;
        }

        // ⑥ 命中进返回结果的条目触发强化（不走 MemoryLifecycleService——避免 bean 循环依赖；
        //    强化语义与 reinforce 完全一致：boost 单次、lastAccessedAt 批量一次 UPDATE）
        if (!reinforcedIds.isEmpty()) {
            entryMapper.reinforceBatch(reinforcedIds, params.boostPerHit(),
                    java.time.LocalDateTime.now(clock));
        }
        String body = String.join("\n", lines);
        return new MemorySearchResult(false, delimitGuard != null
                ? delimitGuard.wrap(body, DelimitHeaders.Src.MEMORY)
                : DelimitHeaders.wrap(body, DelimitHeaders.Src.MEMORY));
    }

    private String renderLine(MemoryEntryDO entry, int perLineBudget) {
        String date = entry.getOccurredAt() == null
                ? "" : entry.getOccurredAt().toLocalDate().toString();
        return "[" + date + "] (" + typeLabel(entry.getMemoryType()) + ") "
                + truncateContent(entry.getContent(), perLineBudget);
    }

    /**
     * 五类记忆的中文标签（检索结果每行给模型看的类型标注）
     */
    static String typeLabel(String memoryType) {
        return switch (memoryType == null ? "" : memoryType) {
            case MemoryEntryDO.TYPE_EPISODE -> "情景";
            case MemoryEntryDO.TYPE_FACT -> "事实";
            case MemoryEntryDO.TYPE_TOPIC -> "话题";
            case MemoryEntryDO.TYPE_PREFERENCE -> "偏好";
            case MemoryEntryDO.TYPE_RELATION_CLUE -> "关系线索";
            default -> memoryType == null ? "未分类" : memoryType;
        };
    }

    /**
     * 正文超长截断自描述（与 2.2.6.5 截断自描述纪律一致）
     */
    static String truncateContent(String content, int tokenCap) {
        String suffix = "…（内容过长已截断）";
        int total = TokenEstimator.estimate(content);
        if (total <= tokenCap) {
            return content;
        }
        int cap = Math.max(1, tokenCap - TokenEstimator.estimate(suffix));
        int cut = (int) Math.floor((double) content.length() * cap / total);
        while (cut > 0 && TokenEstimator.estimate(content.substring(0, cut)) > cap) {
            cut = cut * 99 / 100 - 1;
        }
        return content.substring(0, Math.max(cut, 0)) + suffix;
    }

    private java.time.Instant occurredInstant(MemoryEntryDO entry) {
        return entry.getOccurredAt() == null ? clock.instant()
                : entry.getOccurredAt().atZone(clock.getZone()).toInstant();
    }

    private java.time.Instant lastAccessedInstant(MemoryEntryDO entry) {
        return entry.getLastAccessedAt() == null ? null
                : entry.getLastAccessedAt().atZone(clock.getZone()).toInstant();
    }

    @Override
    public void reembed(GroupKey group, long memoryId) {
        MemoryEntryDO entry = entryMapper.selectById(memoryId);
        if (entry == null || !group.platform().equals(entry.getPlatform())
                || !group.botId().equals(entry.getBotId())
                || !group.groupId().equals(entry.getGroupId())) {
            throw new cn.edu.scut.chatbot.exception.CommandRejectedException(
                    "记忆条目不存在或不属于该群 memoryId=" + memoryId + " group=" + group.asMapKey());
        }
        List<float[]> vectors = embedBatch(List.of(entry.getContent()));
        qdrant.upsert(MemoryIndexPoints.COLLECTION_ALIAS,
                List.of(MemoryIndexPoints.pointOf(entry, vectors.get(0), clock.getZone())));
        log.info("记忆修正重嵌入完成 memoryId={}", memoryId);
    }

    @Override
    public void deleteFromIndex(List<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        qdrant.delete(MemoryIndexPoints.COLLECTION_ALIAS, memoryIds);
    }

    @Override
    public RebuildReport rebuildCollection(String targetCollection, boolean switchAliasAfter) {
        int dim = probeDim();
        QdrantClient.QdrantCollectionInfo info = qdrant.describeCollection(targetCollection);
        if (info == null) {
            qdrant.createCollection(targetCollection, dim, MemoryIndexPoints.STABLE_PAYLOAD_INDEXES);
            log.info("重建目标集合创建 collection={} dim={}", targetCollection, dim);
        } else if (info.dim() != dim) {
            throw new NonRetryableException(MemoryErrorCodes.QDRANT_DIM_MISMATCH,
                    "重建目标集合维度 " + info.dim() + " 与当前 embedding 槽位维度 " + dim
                            + " 不符（目标集合=" + targetCollection + "）");
        }

        long cursor = 0;
        int total = 0;
        while (true) {
            List<MemoryEntryDO> batch = entryMapper.selectActiveAfterId(cursor, REBUILD_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            List<String> contents = batch.stream().map(MemoryEntryDO::getContent).toList();
            List<float[]> vectors = embedBatch(contents);
            List<QdrantClient.QdrantPoint> points = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                points.add(MemoryIndexPoints.pointOf(batch.get(i), vectors.get(i), clock.getZone()));
            }
            qdrant.upsert(targetCollection, points);
            cursor = batch.get(batch.size() - 1).getId();
            total += batch.size();
        }
        if (switchAliasAfter) {
            qdrant.switchAlias(MemoryIndexPoints.COLLECTION_ALIAS, targetCollection);
        }
        log.info("集合重建完成 collection={} total={} aliasSwitched={}",
                targetCollection, total, switchAliasAfter);
        return new RebuildReport(total, total, targetCollection, switchAliasAfter);
    }

    // ===== 共用底座 =====

    private List<float[]> embedBatch(List<String> texts) {
        return embeddingClient.embed(ModelCatalog.SLOT_EMBEDDING,
                LlmCallMeta.of("embedding", "memory-index"), texts);
    }

    private int probeDim() {
        try {
            slotResolver.resolve(ModelCatalog.SLOT_EMBEDDING);
        } catch (ModelCatalog.SlotResolutionException e) {
            throw new NonRetryableException(MemoryErrorCodes.QDRANT_SLOT_UNBOUND,
                    "embedding 槽位未绑定，无法向量化：" + e.getMessage());
        }
        List<float[]> probe = embedBatch(List.of("dimension-probe"));
        if (probe.isEmpty() || probe.get(0).length == 0) {
            throw new NonRetryableException(MemoryErrorCodes.QDRANT_SLOT_UNBOUND,
                    "embedding 槽位探测返回空向量");
        }
        return probe.get(0).length;
    }
}
