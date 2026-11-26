package cn.edu.scut.chatbot.infrastructure.es;

import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.exception.RetryableException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志查询实现：logs-agent-* / logs-system-* 两条数据流（OTLP appender 按
 * logger 前缀附加 log.category，Collector 据此路由——US2 双流拓扑）。
 *
 * <p>文档字段口径（与 deploy/es-init 索引模板、LogStreamCategoryIT 种子一致）：
 *
 * @timestamp（epoch 毫秒）/ severity / logger / message / trace_id / span_id /
 * group_id / turn_id / log.category（嵌套）。
 *
 * <p>ES 不可达语义（FR-006 / T032）：统一翻译 observe.query_unavailable 可重试错误。
 */
@Service
public class LogQueryServiceImpl implements LogQueryService {

    /**
     * 日志数据流索引前缀——category 决定查哪条流（null 查两类）
     */
    private static final String AGENT_INDEX = "logs-agent-*";
    private static final String SYSTEM_INDEX = "logs-system-*";
    private static final String BOTH_INDEX = "logs-*";

    private final ElasticsearchClient esClient;

    public LogQueryServiceImpl(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public LogPage query(LogFilter filter) {
        try {
            SearchResponse<JsonNode> resp = esClient.search(s -> s
                            .index(indexFor(filter.category()))
                            .query(q -> q.bool(b -> {
                                if (filter.fromMillis() != null || filter.toMillis() != null) {
                                    b.filter(f -> f.range(r -> {
                                        r.field("@timestamp");
                                        if (filter.fromMillis() != null) {
                                            r.gte(JsonData.of(filter.fromMillis()));
                                        }
                                        if (filter.toMillis() != null) {
                                            r.lte(JsonData.of(filter.toMillis()));
                                        }
                                        return r;
                                    }));
                                }
                                if (filter.groupId() != null && !filter.groupId().isBlank()) {
                                    b.filter(f -> f.term(t -> t.field("group_id").value(filter.groupId())));
                                }
                                if (filter.traceId() != null && !filter.traceId().isBlank()) {
                                    b.filter(f -> f.term(t -> t.field("trace_id").value(filter.traceId())));
                                }
                                if (filter.category() != null && !filter.category().isBlank()) {
                                    b.filter(f -> f.term(t -> t.field("log.category").value(filter.category())));
                                }
                                if (filter.keyword() != null && !filter.keyword().isBlank()) {
                                    b.must(m -> m.match(ma -> ma.field("message").query(filter.keyword())));
                                }
                                return b;
                            }))
                            .sort(so -> so.field(f -> f.field("@timestamp").order(SortOrder.Desc)))
                            .from(filter.page() * filter.size())
                            .size(filter.size()),
                    JsonNode.class);
            List<LogEntry> items = new ArrayList<>();
            for (Hit<JsonNode> hit : resp.hits().hits()) {
                items.add(toEntry(hit.source()));
            }
            return new LogPage(resp.hits().total() == null ? items.size() : resp.hits().total().value(), items);
        } catch (IOException | RuntimeException e) {
            throw new RetryableException(ErrorCodes.OBSERVE_QUERY_UNAVAILABLE,
                    "ES 查询不可达（operation=logQuery），稍后重试", e);
        }
    }

    private String indexFor(String category) {
        if (category == null || category.isBlank()) {
            return BOTH_INDEX;
        }
        return "agent".equals(category) ? AGENT_INDEX : SYSTEM_INDEX;
    }

    private LogEntry toEntry(JsonNode doc) {
        return new LogEntry(
                doc.path("@timestamp").asLong(0),
                doc.path("severity").asText(null),
                doc.path("logger").asText(null),
                doc.path("message").asText(null),
                textOrNull(doc, "trace_id"),
                textOrNull(doc, "span_id"),
                textOrNull(doc, "group_id"),
                textOrNull(doc, "turn_id"),
                doc.path("log").path("category").asText(null));
    }

    private String textOrNull(JsonNode doc, String field) {
        return doc.path(field).isTextual() ? doc.path(field).asText() : null;
    }
}
