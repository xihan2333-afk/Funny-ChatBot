package cn.edu.scut.chatbot.memory.sediment;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 异步批量向量化写入（contracts/sediment-pipeline.md §3 双存储次序）：
 * MySQL 落库后（调用方已保证先落）→ 异步 embed → 逐批 Qdrant upsert
 * （向量 + 稳定过滤字段 payload）。
 *
 * <ul>
 *   <li>Qdrant 写失败重试超限告警——「在库不在索引」是可接受降级
 *       （MySQL 条目保留、检索查不到但系统不崩溃），等重建或下批合并补；</li>
 *   <li>embedding 槽位未绑定时向量化跳过并告警、条目留库待槽位补绑后增量补嵌
 *       （重建路径 rebuildCollection 天然是补嵌通道）。</li>
 * </ul>
 */
@Component
public class EmbeddingWriter {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingWriter.class);

    /**
     * 单批向量化的条目数上界
     */
    static final int EMBED_BATCH = 100;

    /**
     * Qdrant 写失败重试次数（整批重试；逐点定位由下批重建兜底）
     */
    private static final int UPSERT_RETRY = 3;

    private final MemoryEntryMapper entryMapper;
    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrant;
    private final Clock clock;

    public EmbeddingWriter(MemoryEntryMapper entryMapper,
                           EmbeddingClient embeddingClient,
                           QdrantClient qdrant,
                           Clock clock) {
        this.entryMapper = entryMapper;
        this.embeddingClient = embeddingClient;
        this.qdrant = qdrant;
        this.clock = clock;
    }

    /**
     * 异步补嵌（虚拟线程——不占 Effects 串行通道也不占调度线程）
     */
    public void enqueueAsync(GroupKey group, List<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("memory-embed").start(() -> write(group, memoryIds));
    }

    /**
     * 同步写入形态（测试与重建路径共用）
     */
    public void write(GroupKey group, List<Long> memoryIds) {
        List<MemoryEntryDO> entries = entryMapper.selectByIds(memoryIds);
        List<MemoryEntryDO> indexable = entries.stream()
                .filter(entry -> MemoryEntryDO.STATUS_ACTIVE.equals(entry.getStatus())
                        && group.platform().equals(entry.getPlatform())
                        && group.botId().equals(entry.getBotId())
                        && group.groupId().equals(entry.getGroupId()))
                .toList();
        if (indexable.isEmpty()) {
            return;
        }
        for (int start = 0; start < indexable.size(); start += EMBED_BATCH) {
            List<MemoryEntryDO> batch = indexable.subList(start,
                    Math.min(start + EMBED_BATCH, indexable.size()));
            writeBatch(group, batch);
        }
    }

    private void writeBatch(GroupKey group, List<MemoryEntryDO> batch) {
        List<String> contents = batch.stream().map(MemoryEntryDO::getContent).toList();
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(ModelCatalog.SLOT_EMBEDDING,
                    new LlmCallMeta(group.platform(), group.botId(), group.groupId(), null,
                            "sediment", "embed-" + clock.millis(), false),
                    contents);
        } catch (RuntimeException e) {
            log.warn("向量化跳过（embedding 槽位未就绪），条目留库待补嵌（rebuild 是补嵌通道）：{}",
                    e.getMessage());
            return;
        }
        List<QdrantClient.QdrantPoint> points = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            points.add(MemoryIndexPoints.pointOf(batch.get(i), vectors.get(i), clock.getZone()));
        }
        for (int attempt = 1; attempt <= UPSERT_RETRY; attempt++) {
            try {
                qdrant.upsert(MemoryIndexPoints.COLLECTION_ALIAS, points);
                return;
            } catch (RuntimeException e) {
                if (attempt == UPSERT_RETRY) {
                    log.error("Qdrant 写入重试超限（在库不在索引可接受降级——MySQL 条目保留）"
                                    + " errorCode=memory.qdrant.unavailable memoryIds={} traceId=n/a",
                            batch.stream().map(MemoryEntryDO::getId).toList(), e);
                }
            }
        }
    }
}
