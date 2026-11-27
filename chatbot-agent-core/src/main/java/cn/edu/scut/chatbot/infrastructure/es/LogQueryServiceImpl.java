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
                                    b.filter(f -> f.bool(sb -> sb
                                            .should(sh -> sh.term(t -> t.field("group_id").value(filter.groupId())))
                                            .should(sh -> sh.term(t -> t.field("labels.groupId").value(filter.groupId())))
                                            .should(sh -> sh.term(t -> t.field("Attributes.groupId").value(filter.groupId())))
                                            .should(sh -> sh.term(t -> t.field("logback.mdc.groupId").value(filter.groupId())))
                                            .should(sh -> sh.term(t -> t.field("logback.mdc.groupId.keyword").value(filter.groupId())))
                                            .minimumShouldMatch("1")));
                                }
                                if (filter.traceId() != null && !filter.traceId().isBlank()) {
                                    b.filter(f -> f.bool(sb -> sb
                                            .should(sh -> sh.term(t -> t.field("trace_id").value(filter.traceId())))
                                            .should(sh -> sh.term(t -> t.field("trace.id").value(filter.traceId())))
                                            .minimumShouldMatch("1")));
                                }
                                if ("agent".equals(filter.category())) {
                                    b.filter(f -> f.bool(sb -> sb
                                            .should(sh -> sh.term(t -> t.field("log.category").value("agent")))
                                            .should(sh -> sh.term(t -> t.field("logback.mdc.log.category").value("agent")))
                                            .should(sh -> sh.term(t -> t.field("logback.mdc.log.category.keyword").value("agent")))
                                            .minimumShouldMatch("1")));
                                } else if (filter.category() != null && !filter.category().isBlank()) {
                                    // system 数据流还承接未附加 log.category 的接入层/框架日志：排除 agent 即可，兼容带与不带标签
                                    b.mustNot(mn -> mn.term(t -> t.field("log.category").value("agent")));
                                    b.mustNot(mn -> mn.term(t -> t.field("logback.mdc.log.category").value("agent")));
                                    b.mustNot(mn -> mn.term(t -> t.field("logback.mdc.log.category.keyword").value("agent")));
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
        JsonNode ts = doc.path("@timestamp");
        long tsMillis = ts.isNumber() ? ts.asLong(0) : parseIsoMillis(ts.asText(null));
        String level = textOrNull(doc, "severity");
        if (level == null) {
            level = textOrNull(doc.path("log"), "level");
        }
        String logger = textOrNull(doc, "logger");
        if (logger == null) {
            logger = textOrNull(doc.path("log"), "logger");
        }
        if (logger == null) {
            logger = textOrNull(doc.path("Scope"), "name");
        }
        String traceId = textOrNull(doc, "trace_id");
        if (traceId == null) {
            traceId = textOrNull(doc.path("trace"), "id");
        }
        String spanId = textOrNull(doc, "span_id");
        if (spanId == null) {
            spanId = textOrNull(doc.path("span"), "id");
        }
        String groupId = textOrNull(doc, "group_id");
        if (groupId == null) {
            groupId = textOrNull(doc.path("labels"), "groupId");
        }
        if (groupId == null) {
            groupId = textOrNull(doc.path("Attributes"), "groupId");
        }
        if (groupId == null) {
            groupId = textOrNull(doc.path("logback").path("mdc"), "groupId");
        }
        String turnId = textOrNull(doc, "turn_id");
        if (turnId == null) {
            turnId = textOrNull(doc.path("labels"), "turnId");
        }
        if (turnId == null) {
            turnId = textOrNull(doc.path("Attributes"), "turnId");
        }
        if (turnId == null) {
            turnId = textOrNull(doc.path("logback").path("mdc"), "turnId");
        }
        String category = textOrNull(doc.path("log"), "category");
        if (category == null) {
            category = textOrNull(doc.path("logback").path("mdc").path("log"), "category");
        }
        return new LogEntry(
                tsMillis,
                level,
                logger,
                doc.path("message").asText(null),
                traceId,
                spanId,
                groupId,
                turnId,
                category);
    }

    private static long parseIsoMillis(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0L;
        }
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String textOrNull(JsonNode doc, String field) {
        return doc.path(field).isTextual() ? doc.path(field).asText() : null;
    }
}
