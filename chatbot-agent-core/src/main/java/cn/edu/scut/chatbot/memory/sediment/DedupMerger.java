package cn.edu.scut.chatbot.memory.sediment;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import cn.edu.scut.chatbot.memory.retrieve.RetrieveParams;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 去重合并器（contracts/sediment-pipeline.md §3 内容级幂等、research R13）：
 * 候选条目逐条向量化 → Qdrant 同群近邻 top 5（管道强制注入隔离三元组过滤）→
 * 相似度 ≥ mergeSimilarity（默认 0.85）判合并（既有条目强化 +10、正文按
 * mergedContent 拼接重写）否则新建。
 *
 * <p>向量化不可用（embedding 槽位未绑定）时降级为全部新建——条目留库，
 * 去重等槽位补绑后由后续批次自然竞争（两级幂等的内容级兜底）。
 */
@Component
public class DedupMerger {

    private static final Logger log = LoggerFactory.getLogger(DedupMerger.class);

    /**
     * 合并判定近邻数（top 5——只与最近的几条比）
     */
    private static final int NEIGHBOR_TOP = 5;

    /**
     * 合并强化加值（合并 = 「这件事又说了一遍」——强度 +10，上限 100 由 SQL 钳）
     */
    private static final int MERGE_BOOST = 10;

    private final MemoryEntryMapper entryMapper;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrant;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SystemParamService systemParams;

    public DedupMerger(MemoryEntryMapper entryMapper,
                       EmbeddingClient embeddingClient,
                       QdrantClient qdrant,
                       IdGenerator idGenerator,
                       Clock clock,
                       SystemParamService systemParams) {
        this.entryMapper = entryMapper;
        this.embeddingClient = embeddingClient;
        this.qdrant = qdrant;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.systemParams = systemParams;
    }

    /**
     * 合并产出：新建与合并（含正文重写）的条目 id——两者都要补向量化
     */
    public record MergeOutcome(List<Long> createdIds, List<Long> mergedIds) {

        public MergeOutcome {
            createdIds = createdIds == null ? List.of() : List.copyOf(createdIds);
            mergedIds = mergedIds == null ? List.of() : List.copyOf(mergedIds);
        }
    }

    /**
     * 候选判重落库：新建条目 MySQL 先落（双存储次序——Qdrant 由 EmbeddingWriter 后写）
     */
    public MergeOutcome mergeCandidates(GroupKey group, long jobId,
                                        List<ExtractionContract.CandidateEntry> candidates) {
        List<float[]> vectors = embedOrNull(group, candidates);
        List<Long> created = new ArrayList<>();
        List<Long> merged = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ExtractionContract.CandidateEntry candidate = candidates.get(i);
            float[] vector = vectors == null ? null : vectors.get(i);
            Long mergeTarget = vector != null ? findMergeTarget(group, vector) : null;
            if (mergeTarget != null) {
                mergeInto(group, mergeTarget, candidate);
                merged.add(mergeTarget);
            } else {
                created.add(insertNewEntry(group, jobId, candidate));
            }
        }
        return new MergeOutcome(created, merged);
    }

    /**
     * 批量向量化（一次调用）；槽位未绑定返回 null（降级全新建）
     */
    private List<float[]> embedOrNull(GroupKey group,
                                      List<ExtractionContract.CandidateEntry> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        try {
            List<String> contents = candidates.stream()
                    .map(ExtractionContract.CandidateEntry::content).toList();
            return embeddingClient.embed(ModelCatalog.SLOT_EMBEDDING,
                    new LlmCallMeta(group.platform(), group.botId(), group.groupId(), null,
                            "sediment", "dedup-" + clock.millis(), false),
                    contents);
        } catch (RuntimeException e) {
            log.warn("候选向量化不可用（embedding 槽位未就绪），本批降级为全新建（去重由后续批次兜底）：{}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * 同群近邻判重：返回相似度 ≥ 阈值的既有条目 id（无则 null）
     */
    private Long findMergeTarget(GroupKey group, float[] vector) {
        double similarity = mergeSimilarity();
        QdrantClient.QdrantFilter filter = new QdrantClient.QdrantFilter(
                List.of(new QdrantClient.QdrantFilter.KeywordCondition("platform", group.platform()),
                        new QdrantClient.QdrantFilter.KeywordCondition("botId", group.botId()),
                        new QdrantClient.QdrantFilter.KeywordCondition("groupId", group.groupId())),
                List.of());
        List<QdrantClient.QdrantHit> hits;
        try {
            hits = qdrant.search(MemoryIndexPoints.COLLECTION_ALIAS, vector, filter, NEIGHBOR_TOP);
        } catch (RuntimeException e) {
            log.warn("近邻检索失败按新建处理（索引层可失败、事实源在 MySQL）：{}", e.getMessage());
            return null;
        }
        for (QdrantClient.QdrantHit hit : hits) {
            if (hit.score() >= similarity) {
                MemoryEntryDO existing = entryMapper.selectById(hit.id());
                if (existing != null && MemoryEntryDO.STATUS_ACTIVE.equals(existing.getStatus())
                        && group.groupId().equals(existing.getGroupId())
                        && group.platform().equals(existing.getPlatform())
                        && group.botId().equals(existing.getBotId())) {
                    return existing.getId();
                }
            }
        }
        return null;
    }

    /**
     * 合并：强化 +10（SQL 钳 100）；抽取给了 mergedContent 则正文拼接重写（向量待重嵌）
     */
    private void mergeInto(GroupKey group, long existingId,
                           ExtractionContract.CandidateEntry candidate) {
        MemoryEntryDO existing = entryMapper.selectById(existingId);
        int boosted = Math.min(100, existing.getStrength() + MERGE_BOOST);
        entryMapper.updateStrengthAndStatus(existingId, boosted, existing.getStatus(),
                LocalDateTime.now(clock));
        if (candidate.mergedContent() != null && !candidate.mergedContent().isBlank()
                && !candidate.mergedContent().equals(existing.getContent())) {
            entryMapper.updateContent(existingId, candidate.mergedContent());
            log.info("记忆合并正文重写 memoryId={} 原 {} 字 → 新 {} 字",
                    existingId, existing.getContent().length(), candidate.mergedContent().length());
        }
        log.info("记忆合并强化 memoryId={} strength {} → {}（相似内容再出现）",
                existingId, existing.getStrength(), boosted);
    }

    /**
     * 新建条目（MySQL 先落——id 雪花发放先于落库；source_ref 回指工单）
     */
    private long insertNewEntry(GroupKey group, long jobId,
                                ExtractionContract.CandidateEntry candidate) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(idGenerator.nextId());
        entry.setPlatform(group.platform());
        entry.setBotId(group.botId());
        entry.setGroupId(group.groupId());
        entry.setMemoryType(candidate.memoryType());
        entry.setContent(candidate.content());
        entry.setUserId(candidate.userId());
        if (MemoryEntryDO.TYPE_TOPIC.equals(candidate.memoryType())
                && !candidate.keywords().isEmpty()) {
            entry.setKeywords(keywordsJson(candidate.keywords()));
            entry.setTopicLifecycle(MemoryEntryDO.TOPIC_HOT);   // 沉淀时默认热
        }
        entry.setOccurredAt(parseHintOrNow(candidate.occurredAtHint()));
        entry.setSedimentAt(LocalDateTime.now(clock));
        entry.setStrength(50);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        entry.setSourceRef("{\"jobId\":" + jobId + "}");
        entryMapper.insertEntry(entry);
        return entry.getId();
    }

    /**
     * 发生时间：候选给了日期按日期（缺时/分补零）；没给按沉淀时刻
     */
    private LocalDateTime parseHintOrNow(String occurredAtHint) {
        if (occurredAtHint != null && !occurredAtHint.isBlank()) {
            try {
                LocalDate date = LocalDate.parse(occurredAtHint.trim(),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                return date.atStartOfDay();
            } catch (Exception e) {
                // 日期格式不合（模型输出小毛病）——按现在处理，不杀批
            }
        }
        return LocalDateTime.now(clock);
    }

    private double mergeSimilarity() {
        return RetrieveParams.load(systemParams).mergeSimilarity();
    }

    private static String keywordsJson(List<String> keywords) {
        try {
            JsonNode node = JsonMappers.shared().valueToTree(keywords);
            return JsonMappers.shared().writeValueAsString(node);
        } catch (Exception e) {
            return "[]";
        }
    }
}
