package cn.edu.scut.chatbot.infrastructure.es;

import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.exception.RetryableException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.NamedValue;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路查询实现：traces-* 数据流（Collector ES exporter 写入的 Span 文档）。
 *
 * <p>文档字段口径（与 deploy/es-init 索引模板、IT 种子一致）：traceId / spanId /
 * parentSpanId / name / startTimeMillis / durationMillis / attributes（对象，
 * chatbot.* 键经 ES 动态映射展开为嵌套路径，如 attributes.chatbot.group_id）。
 *
 * <p>ES 不可达语义（FR-006 / T032）：连接失败与超时统一翻译为
 * observe.query_unavailable 可重试错误——EsClientFactory 的 3s / 10s 超时
 * 保证快速失败，调用方（管理接口）拿到明确错误而非挂死。
 */
@Service
public class TraceQueryServiceImpl implements TraceQueryService {

    /**
     * traces 数据流通配（索引模板按日滚动，统一前缀 traces-）
     */
    static final String TRACES_INDEX = "traces-*";

    /**
     * 反查 traceId 上限——链路页一屏消费量级，超出提示缩小条件
     */
    private static final int TRACE_SEARCH_LIMIT = 200;

    /**
     * 单条链路 Span 数上限（一个 Turn 生命周期含全部轮次的 llm.call / tool 子 Span）
     */
    private static final int SPAN_LIST_LIMIT = 1000;

    private final ElasticsearchClient esClient;

    public TraceQueryServiceImpl(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public List<SpanView> getTrace(String traceId) {
        try {
            SearchResponse<JsonNode> resp = esClient.search(s -> s
                            .index(TRACES_INDEX)
                            .query(q -> q.term(t -> t.field("traceId").value(traceId)))
                            .sort(so -> so.field(f -> f.field("startTimeMillis").order(SortOrder.Asc)))
                            .size(SPAN_LIST_LIMIT),
                    JsonNode.class);
            List<SpanView> spans = new ArrayList<>();
            for (Hit<JsonNode> hit : resp.hits().hits()) {
                spans.add(toSpanView(hit.source()));
            }
            return spans;
        } catch (IOException | RuntimeException e) {
            throw unavailable("getTrace", e);
        }
    }

    @Override
    public List<String> searchTraces(TraceFilter filter) {
        try {
            SearchResponse<Void> resp = esClient.search(s -> s
                            .index(TRACES_INDEX)
                            .query(q -> q.bool(b -> {
                                termIfPresent(b, "attributes.chatbot.group.platform", filter.platform());
                                termIfPresent(b, "attributes.chatbot.group.bot_id", filter.botId());
                                termIfPresent(b, "attributes.chatbot.group_id", filter.groupId());
                                termIfPresent(b, "attributes.chatbot.slot", filter.slotCode());
                                if (filter.fromMillis() != null || filter.toMillis() != null) {
                                    b.filter(f -> f.range(r -> {
                                        r.field("startTimeMillis");
                                        if (filter.fromMillis() != null) {
                                            r.gte(JsonData.of(filter.fromMillis()));
                                        }
                                        if (filter.toMillis() != null) {
                                            r.lte(JsonData.of(filter.toMillis()));
                                        }
                                        return r;
                                    }));
                                }
                                return b;
                            }))
                            .aggregations("by_trace", a -> a
                                    .terms(t -> t.field("traceId").size(TRACE_SEARCH_LIMIT)
                                            .order(List.of(new NamedValue<>("max_start", SortOrder.Desc))))
                                    .aggregations("max_start", sub -> sub.max(m -> m.field("startTimeMillis"))))
                            .size(0),
                    Void.class);
            List<String> traceIds = new ArrayList<>();
            resp.aggregations().get("by_trace").sterms().buckets().array()
                    .forEach(bucket -> traceIds.add(bucket.key().stringValue()));
            return traceIds;
        } catch (IOException | RuntimeException e) {
            throw unavailable("searchTraces", e);
        }
    }

    private void termIfPresent(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                               String field, String value) {
        if (value != null && !value.isBlank()) {
            b.filter(f -> f.term(t -> t.field(field).value(value)));
        }
    }

    private SpanView toSpanView(JsonNode doc) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        doc.path("attributes").fields().forEachRemaining(entry ->
                attributes.put(entry.getKey(), entry.getValue().isValueNode()
                        ? entry.getValue().asText() : entry.getValue().toString()));
        return new SpanView(
                doc.path("name").asText(null),
                doc.path("spanId").asText(null),
                doc.path("parentSpanId").isTextual() ? doc.path("parentSpanId").asText() : null,
                doc.path("startTimeMillis").asLong(0),
                doc.path("durationMillis").asLong(0),
                attributes);
    }

    private RetryableException unavailable(String operation, Exception cause) {
        return new RetryableException(ErrorCodes.OBSERVE_QUERY_UNAVAILABLE,
                "ES 查询不可达（operation=" + operation + "），稍后重试", cause);
    }
}
