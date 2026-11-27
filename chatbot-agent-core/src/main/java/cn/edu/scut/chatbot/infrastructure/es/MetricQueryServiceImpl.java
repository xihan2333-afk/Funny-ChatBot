package cn.edu.scut.chatbot.infrastructure.es;

import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.engine.intake.DecisionLayerCount;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.TranscriptKindTime;
import cn.edu.scut.chatbot.engine.session.WindowLevelCount;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.engine.turn.TurnStatusCount;
import cn.edu.scut.chatbot.exception.RetryableException;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogAggregation;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标查询实现（六组口径 + runtime）。
 *
 * <p>取数源分工（observe-query 契约）：成本 / 缓存命中走 MySQL llm_call_log
 * （is_replay=0 线上口径）；Turn 端到端 / TTFT 走 MySQL；工具时延 / 工具失败率走
 * ES tool.* Span；runtime 读 OTel 指标数据流（metrics-*）。ES 不可达统一翻译
 * observe.query_unavailable 可重试错误（FR-006 / T032）——MySQL 侧数据照常返回，
 * 混合取数的报表在 ES 侧失败时整体抛错（调用方明确知道这次取数不完整可重试）。
 */
@Service
public class MetricQueryServiceImpl implements MetricQueryService {

    /**
     * 工具 Span / runtime 快照的单次取数上限（分位与失败率样本量级保护）
     */
    private static final int ES_SAMPLE_LIMIT = 2000;

    /**
     * Agent 发言后「被回复」判定窗口（毫秒）——10 分钟内同群出现 user 条目即算跟帖
     */
    private static final long REPLY_WINDOW_MILLIS = 10 * 60 * 1000;

    private final ElasticsearchClient esClient;
    private final LlmCallLogMapper llmCallLogMapper;
    private final TurnMapper turnMapper;
    private final TriggerEventMapper triggerEventMapper;
    private final MemoryEntryMapper memoryEntryMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final Clock clock;

    public MetricQueryServiceImpl(ElasticsearchClient esClient,
                                  LlmCallLogMapper llmCallLogMapper,
                                  TurnMapper turnMapper,
                                  TriggerEventMapper triggerEventMapper,
                                  MemoryEntryMapper memoryEntryMapper,
                                  AgentSessionMapper agentSessionMapper,
                                  Clock clock) {
        this.esClient = esClient;
        this.llmCallLogMapper = llmCallLogMapper;
        this.turnMapper = turnMapper;
        this.triggerEventMapper = triggerEventMapper;
        this.memoryEntryMapper = memoryEntryMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.clock = clock;
    }

    @Override
    public CostReport cost(CostQuery query) {
        LocalDateTime from = toDateTime(query.fromMillis());
        LocalDateTime to = toDateTime(query.toMillis());
        List<CostReport.CostRow> rows = llmCallLogMapper
                .selectCostAggregationFiltered(query.dim().name(), from, to,
                        query.groupId(), query.platform(), query.botId(), query.slotCode(), query.modelId())
                .stream()
                .map(a -> new CostReport.CostRow(a.getDimensionKey(), a.getCalls(),
                        a.getInputTokens(), a.getOutputTokens(), a.getCachedTokens(), a.getCostAmount()))
                .toList();
        return new CostReport(query.dim(), rows);
    }

    @Override
    public LatencyReport latency(MetricWindow window) {
        LocalDateTime from = toDateTime(window.fromMillis());
        LocalDateTime to = toDateTime(window.toMillis());

        List<Long> turnDurations = turnMapper.selectFinishedDurations(from, to, window.groupId());
        List<Long> ttfts = llmCallLogMapper.selectTtftMillis(from, to, window.groupId());
        List<JsonNode> toolSpans = searchToolSpans(window);

        List<Long> toolDurations = toolSpans.stream()
                .map(doc -> doc.path("durationMillis").asLong(0)).toList();
        return new LatencyReport(
                turnDurations.size(), percentiles(turnDurations),
                ttfts.size(), percentiles(ttfts),
                toolDurations.size(), percentiles(toolDurations));
    }

    @Override
    public QualityReport quality(MetricWindow window) {
        LocalDateTime from = toDateTime(window.fromMillis());
        LocalDateTime to = toDateTime(window.toMillis());

        long total = 0;
        long failed = 0;
        long silent = 0;
        for (TurnStatusCount cnt : turnMapper.countByStatus(from, to, window.groupId())) {
            total += cnt.getCount();
            if ("FAILED".equals(cnt.getStatus())) {
                failed += cnt.getCount();
            }
            if ("SILENT".equals(cnt.getStatus())) {
                silent += cnt.getCount();
            }
        }

        long l1Total = 0;
        long l1Accept = 0;
        long degradeTotal = 0;
        for (DecisionLayerCount cnt : triggerEventMapper.countDecisionByWindow(from, to, window.groupId())) {
            if ("L1".equals(cnt.getDecisionLayer())) {
                l1Total += cnt.getCount();
                if ("accept".equals(cnt.getDecision())) {
                    l1Accept += cnt.getCount();
                }
            } else if ("degrade".equals(cnt.getDecisionLayer())) {
                degradeTotal += cnt.getCount();
            }
        }

        List<JsonNode> toolSpans = searchToolSpans(window);
        long toolErrors = toolSpans.stream()
                .filter(doc -> doc.path("attributes").path("chatbot.is_error").asBoolean(false)
                        || "true".equals(doc.path("attributes").path("chatbot.is_error").asText()))
                .count();

        LlmCallLogAggregation tokens = llmCallLogMapper.selectTokenTotals(from, to, window.groupId());
        Double cacheHitRate = tokens != null && tokens.getInputTokens() > 0
                ? (double) tokens.getCachedTokens() / tokens.getInputTokens() : null;

        long l1Judged = l1Total + degradeTotal;
        return new QualityReport(
                total,
                total > 0 ? (double) (total - failed) / total : null,
                total > 0 ? (double) silent / total : null,
                l1Judged,
                l1Total > 0 ? (double) l1Accept / l1Total : null,
                l1Judged > 0 ? (double) degradeTotal / l1Judged : null,
                toolSpans.size(),
                toolSpans.isEmpty() ? null : (double) toolErrors / toolSpans.size(),
                cacheHitRate);
    }

    @Override
    public ContextReport contextMetrics(MetricWindow window) {
        LocalDateTime from = toDateTime(window.fromMillis());
        LocalDateTime to = toDateTime(window.toMillis());

        LlmCallLogAggregation tokens = llmCallLogMapper.selectTokenTotals(from, to, window.groupId());
        double avgProjection = tokens != null && tokens.getCalls() > 0
                ? (double) tokens.getInputTokens() / tokens.getCalls() : 0.0;

        long summaryCount = agentSessionMapper.selectTranscriptKindTimes(from, to, window.groupId())
                .stream().filter(t -> "summary".equals(t.getKind())).count();

        Map<String, Long> distribution = new LinkedHashMap<>();
        for (WindowLevelCount cnt : agentSessionMapper.countWindowLevel()) {
            distribution.put(cnt.getWindowLevel(), cnt.getCount());
        }
        return new ContextReport(avgProjection, summaryCount, distribution);
    }

    @Override
    public SocialReport socialMetrics(MetricWindow window) {
        LocalDateTime from = toDateTime(window.fromMillis());
        LocalDateTime to = toDateTime(window.toMillis());

        long sediment = memoryEntryMapper.countSedimentBetween(from, to, window.groupId());

        long initiativeTotal = 0;
        long initiativeAccept = 0;
        for (DecisionLayerCount cnt : triggerEventMapper.countInternalDecision(
                "silenceBreak", from, to, window.groupId())) {
            initiativeTotal += cnt.getCount();
            if ("accept".equals(cnt.getDecision())) {
                initiativeAccept += cnt.getCount();
            }
        }
        return new SocialReport(sediment, initiativeTotal,
                initiativeTotal > 0 ? (double) initiativeAccept / initiativeTotal : null);
    }

    @Override
    public FeedbackReport interactionFeedback(MetricWindow window) {
        LocalDateTime from = toDateTime(window.fromMillis());
        LocalDateTime to = toDateTime(window.toMillis());
        List<TranscriptKindTime> sequence = agentSessionMapper
                .selectTranscriptKindTimes(from, to, window.groupId());

        long agentMessages = 0;
        long replied = 0;
        for (int i = 0; i < sequence.size(); i++) {
            TranscriptKindTime entry = sequence.get(i);
            if (!"assistant".equals(entry.getKind())) {
                continue;
            }
            agentMessages++;
            if (hasUserReplyWithin(sequence, i, REPLY_WINDOW_MILLIS)) {
                replied++;
            }
        }
        return new FeedbackReport(agentMessages, replied,
                agentMessages > 0 ? (double) replied / agentMessages : null);
    }

    @Override
    public RuntimeReport runtime() {
        try {
            SearchResponse<JsonNode> resp = esClient.search(s -> s
                            .index("metrics-*")
                            .query(q -> q.matchAll(m -> m))
                            .sort(so -> so.field(f -> f.field("@timestamp").order(SortOrder.Desc)))
                            .size(ES_SAMPLE_LIMIT),
                    JsonNode.class);
            List<RuntimeReport.MetricSnapshot> snapshots = new ArrayList<>();
            for (Hit<JsonNode> hit : resp.hits().hits()) {
                JsonNode doc = hit.source();
                String name = firstText(doc, "name", "metric.name");
                JsonNode valueNode = firstNode(doc, "value", "as_double", "gauge");
                if (name == null || valueNode == null || !valueNode.isNumber()) {
                    continue;
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                doc.path("attributes").fields().forEachRemaining(e ->
                        attributes.put(e.getKey(), e.getValue().asText()));
                snapshots.add(new RuntimeReport.MetricSnapshot(
                        name, valueNode.asDouble(), attributes,
                        doc.path("@timestamp").asLong(0)));
            }
            return new RuntimeReport(snapshots);
        } catch (IOException | RuntimeException e) {
            throw unavailable("runtime", e);
        }
    }

    /**
     * 是否在 replyWindowMillis 内出现 user 条目（从 index+1 起顺序扫描——序列按时间正序）
     */
    private boolean hasUserReplyWithin(List<TranscriptKindTime> sequence, int index, long replyWindowMillis) {
        long deadline = sequence.get(index).getOccurredAt()
                .atZone(clock.getZone()).toInstant().toEpochMilli() + replyWindowMillis;
        for (int j = index + 1; j < sequence.size(); j++) {
            TranscriptKindTime next = sequence.get(j);
            long at = next.getOccurredAt().atZone(clock.getZone()).toInstant().toEpochMilli();
            if (at > deadline) {
                return false;
            }
            if ("user".equals(next.getKind())) {
                return true;
            }
        }
        return false;
    }

    /**
     * tool.* Span 检索（工具时延与失败率的取数源；群 / 时间过滤走 chatbot.* 属性路径）
     */
    private List<JsonNode> searchToolSpans(MetricWindow window) {
        try {
            SearchResponse<JsonNode> resp = esClient.search(s -> s
                            .index(TraceQueryServiceImpl.TRACES_INDEX)
                            .query(q -> q.bool(b -> {
                                b.filter(f -> f.wildcard(w -> w.field("name").value("tool.*")));
                                if (window.groupId() != null && !window.groupId().isBlank()) {
                                    b.filter(f -> f.term(t -> t.field("attributes.chatbot.group_id")
                                            .value(window.groupId())));
                                }
                                if (window.fromMillis() != null) {
                                    b.filter(f -> f.range(r -> r.field("startTimeMillis")
                                            .gte(JsonData.of(window.fromMillis()))));
                                }
                                if (window.toMillis() != null) {
                                    b.filter(f -> f.range(r -> r.field("startTimeMillis")
                                            .lte(JsonData.of(window.toMillis()))));
                                }
                                return b;
                            }))
                            .size(ES_SAMPLE_LIMIT),
                    JsonNode.class);
            return resp.hits().hits().stream().map(Hit::source).toList();
        } catch (IOException | RuntimeException e) {
            throw unavailable("toolSpans", e);
        }
    }

    private Percentiles percentiles(List<Long> samples) {
        if (samples == null || samples.isEmpty()) {
            return new Percentiles(null, null, null);
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compare);
        return new Percentiles(
                percentile(sorted, 0.50),
                percentile(sorted, 0.90),
                percentile(sorted, 0.99));
    }

    private Long percentile(List<Long> sorted, double q) {
        int rank = (int) Math.ceil(q * sorted.size());
        return sorted.get(Math.min(Math.max(rank - 1, 0), sorted.size() - 1));
    }

    private LocalDateTime toDateTime(Long epochMillis) {
        return epochMillis == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), clock.getZone());
    }

    private String firstText(JsonNode doc, String... fields) {
        for (String field : fields) {
            if (doc.path(field).isTextual()) {
                return doc.path(field).asText();
            }
        }
        return null;
    }

    private JsonNode firstNode(JsonNode doc, String... fields) {
        for (String field : fields) {
            JsonNode node = doc.path(field);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private RetryableException unavailable(String operation, Exception cause) {
        return new RetryableException(ErrorCodes.OBSERVE_QUERY_UNAVAILABLE,
                "ES 查询不可达（operation=" + operation + "），稍后重试", cause);
    }
}
