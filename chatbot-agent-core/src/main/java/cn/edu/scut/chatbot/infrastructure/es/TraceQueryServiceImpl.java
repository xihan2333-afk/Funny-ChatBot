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
                            .query(q -> q.bool(b -> b
                                    .should(sh -> sh.term(t -> t.field("traceId").value(traceId)))
                                    .should(sh -> sh.term(t -> t.field("TraceId").value(traceId)))
                                    .minimumShouldMatch("1")))
                            .sort(so -> so.field(f -> f.field("startTimeMillis").order(SortOrder.Asc).unmappedType(co.elastic.clients.elasticsearch._types.mapping.FieldType.Long)))
                            .sort(so -> so.field(f -> f.field("@timestamp").order(SortOrder.Asc).unmappedType(co.elastic.clients.elasticsearch._types.mapping.FieldType.Date)))
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
                                termAnyIfPresent(b, filter.platform(), "attributes.chatbot.group.platform", "Attributes.chatbot.group.platform");
                                termAnyIfPresent(b, filter.botId(), "attributes.chatbot.group.bot_id", "Attributes.chatbot.group.bot_id");
                                termAnyIfPresent(b, filter.groupId(), "attributes.chatbot.group_id", "Attributes.chatbot.group_id");
                                termAnyIfPresent(b, filter.slotCode(), "attributes.chatbot.slot", "Attributes.chatbot.slot");
                                if (filter.fromMillis() != null || filter.toMillis() != null) {
                                    b.filter(f -> f.bool(rb -> rb
                                            .should(sh -> sh.range(r -> {
                                                r.field("startTimeMillis");
                                                if (filter.fromMillis() != null) {
                                                    r.gte(JsonData.of(filter.fromMillis()));
                                                }
                                                if (filter.toMillis() != null) {
                                                    r.lte(JsonData.of(filter.toMillis()));
                                                }
                                                return r;
                                            }))
                                            .should(sh -> sh.range(r -> {
                                                r.field("@timestamp");
                                                if (filter.fromMillis() != null) {
                                                    r.gte(JsonData.of(filter.fromMillis()));
                                                }
                                                if (filter.toMillis() != null) {
                                                    r.lte(JsonData.of(filter.toMillis()));
                                                }
                                                return r;
                                            }))
                                            .minimumShouldMatch("1")));
                                }
                                return b;
                            }))
                            .aggregations("by_trace", a -> a
                                    .terms(t -> t.field("traceId").size(TRACE_SEARCH_LIMIT)
                                            .order(List.of(new NamedValue<>("max_start", SortOrder.Desc))))
                                    .aggregations("max_start", sub -> sub.max(m -> m.field("startTimeMillis"))))
                            .aggregations("by_trace_otel", a -> a
                                    .terms(t -> t.field("TraceId").size(TRACE_SEARCH_LIMIT)
                                            .order(List.of(new NamedValue<>("max_ts", SortOrder.Desc))))
                                    .aggregations("max_ts", sub -> sub.max(m -> m.field("@timestamp"))))
                            .size(0),
                    Void.class);
            Map<String, Double> ranked = new LinkedHashMap<>();
            if (resp.aggregations().containsKey("by_trace")) {
                resp.aggregations().get("by_trace").sterms().buckets().array()
                        .forEach(bucket -> ranked.put(bucket.key().stringValue(),
                                bucket.aggregations().get("max_start").max().value()));
            }
            if (resp.aggregations().containsKey("by_trace_otel")) {
                resp.aggregations().get("by_trace_otel").sterms().buckets().array()
                        .forEach(bucket -> ranked.merge(bucket.key().stringValue(),
                                bucket.aggregations().get("max_ts").max().value(), Math::max));
            }
            return ranked.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(TRACE_SEARCH_LIMIT)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (IOException | RuntimeException e) {
            throw unavailable("searchTraces", e);
        }
    }

    private void termAnyIfPresent(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                  String value, String... fields) {
        if (value != null && !value.isBlank()) {
            b.filter(f -> f.bool(sb -> {
                for (String field : fields) {
                    sb.should(sh -> sh.term(t -> t.field(field).value(value)));
                }
                return sb.minimumShouldMatch("1");
            }));
        }
    }

    private SpanView toSpanView(JsonNode doc) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        JsonNode attrNode = !doc.path("attributes").isMissingNode() && doc.path("attributes").size() > 0
                ? doc.path("attributes")
                : doc.path("Attributes");
        attrNode.fields().forEachRemaining(entry ->
                attributes.put(entry.getKey(), entry.getValue().isValueNode()
                        ? entry.getValue().asText() : entry.getValue().toString()));
        long startMs = doc.has("startTimeMillis")
                ? doc.path("startTimeMillis").asLong(0)
                : parseIsoMillis(doc.path("@timestamp").asText(null));
        long durationMs = doc.has("durationMillis")
                ? doc.path("durationMillis").asLong(0)
                : doc.path("Duration").asLong(0) / 1000L;
        String parentSpan = textOr(doc, "parentSpanId", "ParentSpanId");
        return new SpanView(
                textOr(doc, "name", "Name"),
                textOr(doc, "spanId", "SpanId"),
                parentSpan,
                startMs,
                durationMs,
                attributes);
    }

    private static String textOr(JsonNode doc, String primary, String fallback) {
        if (doc.path(primary).isTextual()) {
            return doc.path(primary).asText();
        }
        return doc.path(fallback).isTextual() ? doc.path(fallback).asText() : null;
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

    private RetryableException unavailable(String operation, Exception cause) {
        return new RetryableException(ErrorCodes.OBSERVE_QUERY_UNAVAILABLE,
                "ES 查询不可达（operation=" + operation + "），稍后重试", cause);
    }
}
