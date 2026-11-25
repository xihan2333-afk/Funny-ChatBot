package cn.edu.scut.chatbot.infrastructure.qdrant;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 向量库薄客户端接口（contracts/memory-retrieval.md §3，3.2.3 六端点直译）：
 * 自写 REST 不引官方 Java 客户端（gRPC 依赖链长，3.2.3 明文否决）。
 *
 * <p>分工纪律：Qdrant 只管召回（向量近邻 + payload 过滤），业务打分全在应用层；
 * payload 只放稳定过滤字段（platform/botId/groupId/memoryType/userId/occurredAt），
 * strength、lastAccessedAt、status、topic_lifecycle 刻意不进（易变字段从 MySQL 现读）。
 */
public interface QdrantClient {

    /**
     * 创建集合并建 payload 索引（keyword / integer）；向量距离固定 Cosine
     */
    void createCollection(String name, int dim, Collection<QdrantPayloadIndex> payloadIndexes);

    /**
     * 集合信息（含向量维度）；集合不存在返回 null（启动自检据此走创建分支）
     */
    QdrantCollectionInfo describeCollection(String name);

    /**
     * 批量写点（点 id = 记忆条目 id；等待落盘完成——读写一致优先于吞吐）
     */
    void upsert(String collection, List<QdrantPoint> points);

    /**
     * 向量近邻检索（filter 可空 = 不过滤；返回命中 id / score / payload）
     */
    List<QdrantHit> search(String collection, float[] vector, QdrantFilter filter, int limit);

    /**
     * 按点 id 删除（删除两库同删的索引侧；归档退出检索同用）
     */
    void delete(String collection, List<Long> pointIds);

    /**
     * 别名切换（重嵌入迁移零停机：memory_entry 别名从旧集合指向新集合）
     */
    void switchAlias(String alias, String targetCollection);

    /**
     * payload 索引声明（createCollection 时建）
     */
    record QdrantPayloadIndex(String field, Schema schema) {

        public enum Schema {
            /**
             * 字符串等值过滤（platform / botId / groupId / memoryType / userId）
             */
            KEYWORD,
            /**
             * 整数范围过滤（occurredAt 时间窗口）
             */
            INTEGER
        }
    }

    /**
     * 集合信息（describeCollection 产物）
     */
    record QdrantCollectionInfo(int dim, long pointsCount, String status) {
    }

    /**
     * 待写点：id + 向量 + 稳定过滤字段 payload
     */
    record QdrantPoint(long id, float[] vector, Map<String, Object> payload) {
    }

    /**
     * 检索命中
     */
    record QdrantHit(long id, double score, Map<String, Object> payload) {
    }

    /**
     * 过滤条件（keyword 等值 + integer 范围；空 = 不过滤）。
     * 隔离三元组过滤由检索管道强制注入（调用方不传也被补上——跨群隔离是结构保证）。
     */
    record QdrantFilter(List<KeywordCondition> keywords, List<RangeCondition> ranges) {

        public QdrantFilter {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            ranges = ranges == null ? List.of() : List.copyOf(ranges);
        }

        public static QdrantFilter of() {
            return new QdrantFilter(List.of(), List.of());
        }

        /**
         * 等值条件（keyword 索引字段）
         */
        public record KeywordCondition(String field, String value) {
        }

        /**
         * 范围条件（integer 索引字段；from / to 为 null 表示该侧无界）
         */
        public record RangeCondition(String field, Long from, Long to) {
        }
    }
}
