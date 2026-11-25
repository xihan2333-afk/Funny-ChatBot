package cn.edu.scut.chatbot.memory.retrieve;

import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 点与 payload 的稳定形态（双存储纪律的代码化）：
 * <ul>
 *   <li>点 id = 记忆条目 id（1:1 映射，重建可对齐）；</li>
 *   <li>payload 只放稳定过滤字段：隔离三元组 / memoryType / userId / occurredAt——
 *       strength、lastAccessedAt、status、topic_lifecycle 刻意不进（易变字段，
 *       重排与过滤从 MySQL 现读）；负向断言集（payload 无易变字段）锚定本类。</li>
 * </ul>
 */
public final class MemoryIndexPoints {

    private MemoryIndexPoints() {
    }

    /**
     * 集合别名（重嵌入迁移时物理集合换 memory_entry_v{n}、别名不动——调用方零感知）
     */
    public static final String COLLECTION_ALIAS = "memory_entry";

    /**
     * 稳定过滤字段的 payload 索引（建集合时登记）
     */
    public static final List<QdrantClient.QdrantPayloadIndex> STABLE_PAYLOAD_INDEXES = List.of(
            new QdrantClient.QdrantPayloadIndex("platform", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
            new QdrantClient.QdrantPayloadIndex("botId", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
            new QdrantClient.QdrantPayloadIndex("groupId", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
            new QdrantClient.QdrantPayloadIndex("memoryType", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
            new QdrantClient.QdrantPayloadIndex("userId", QdrantClient.QdrantPayloadIndex.Schema.KEYWORD),
            new QdrantClient.QdrantPayloadIndex("occurredAt", QdrantClient.QdrantPayloadIndex.Schema.INTEGER));

    /**
     * 条目 → Qdrant 点（id 1:1、向量来自 embedding 槽位、payload 仅稳定过滤字段）。
     * zone 取注入 Clock 的时区（写入侧与查询侧同一口径，epoch 过滤窗口才对得齐）
     */
    public static QdrantClient.QdrantPoint pointOf(MemoryEntryDO entry, float[] vector, ZoneId zone) {
        return new QdrantClient.QdrantPoint(entry.getId(), vector, stablePayload(entry, zone));
    }

    /**
     * 稳定过滤字段 payload（易变字段刻意不进——改 strength 不改 payload 是重建无损的前提）
     */
    public static Map<String, Object> stablePayload(MemoryEntryDO entry, ZoneId zone) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", entry.getPlatform());
        payload.put("botId", entry.getBotId());
        payload.put("groupId", entry.getGroupId());
        payload.put("memoryType", entry.getMemoryType());
        if (entry.getUserId() != null) {
            payload.put("userId", entry.getUserId());
        }
        if (entry.getOccurredAt() != null) {
            payload.put("occurredAt", epochMillis(entry.getOccurredAt(), zone));
        }
        return payload;
    }

    /**
     * occurredAt（DATETIME 本地时区）→ epoch 毫秒（Qdrant integer 索引过滤形态）
     */
    public static long epochMillis(LocalDateTime occurredAt, ZoneId zone) {
        return occurredAt.atZone(zone).toInstant().toEpochMilli();
    }
}
