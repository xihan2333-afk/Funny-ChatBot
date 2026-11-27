package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.infrastructure.es.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 观测查询门面（链路页 / 日志页 / 指标页，observe-query 契约第 1 节）：
 * 管理前端 → 本接口 → ES / MySQL，凭证不出服务端。控制器只转发三个查询门面，
 * 不触达 DO / Mapper（宪法第五条）。认证过滤器归 US9，本阶段接口先裸奔注册。
 */
@RestController
@RequestMapping("/api/management/observe")
public class ObservabilityQueryController {

    private final TraceQueryService traceQueryService;
    private final LogQueryService logQueryService;
    private final MetricQueryService metricQueryService;

    public ObservabilityQueryController(TraceQueryService traceQueryService,
                                        LogQueryService logQueryService,
                                        MetricQueryService metricQueryService) {
        this.traceQueryService = traceQueryService;
        this.logQueryService = logQueryService;
        this.metricQueryService = metricQueryService;
    }

    // ===== 链路页 =====

    @GetMapping("/trace/{traceId}")
    public List<SpanView> getTrace(@PathVariable String traceId) {
        return traceQueryService.getTrace(traceId);
    }

    @GetMapping("/traces")
    public List<String> searchTraces(@RequestParam(required = false) String platform,
                                     @RequestParam(required = false) String botId,
                                     @RequestParam(required = false) String groupId,
                                     @RequestParam(required = false) Long fromMillis,
                                     @RequestParam(required = false) Long toMillis,
                                     @RequestParam(required = false) String slotCode) {
        return traceQueryService.searchTraces(
                new TraceFilter(platform, botId, groupId, fromMillis, toMillis, slotCode));
    }

    // ===== 日志页 =====

    @GetMapping("/logs")
    public LogPage queryLogs(@RequestParam(required = false) Long fromMillis,
                             @RequestParam(required = false) Long toMillis,
                             @RequestParam(required = false) String groupId,
                             @RequestParam(required = false) String traceId,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String category,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return logQueryService.query(new LogFilter(
                fromMillis, toMillis, groupId, traceId, keyword, category, page, size));
    }

    // ===== 指标页 =====

    @GetMapping("/metrics/cost")
    public CostReport cost(@RequestParam(defaultValue = "GROUP") CostQuery.CostDim dim,
                           @RequestParam(required = false) Long fromMillis,
                           @RequestParam(required = false) Long toMillis,
                           @RequestParam(required = false) String groupId) {
        return metricQueryService.cost(new CostQuery(dim, fromMillis, toMillis, groupId));
    }

    @GetMapping("/metrics/latency")
    public LatencyReport latency(@RequestParam(required = false) Long fromMillis,
                                 @RequestParam(required = false) Long toMillis,
                                 @RequestParam(required = false) String groupId) {
        return metricQueryService.latency(new MetricWindow(fromMillis, toMillis, groupId));
    }

    @GetMapping("/metrics/quality")
    public QualityReport quality(@RequestParam(required = false) Long fromMillis,
                                 @RequestParam(required = false) Long toMillis,
                                 @RequestParam(required = false) String groupId) {
        return metricQueryService.quality(new MetricWindow(fromMillis, toMillis, groupId));
    }

    @GetMapping("/metrics/context")
    public ContextReport contextMetrics(@RequestParam(required = false) Long fromMillis,
                                        @RequestParam(required = false) Long toMillis,
                                        @RequestParam(required = false) String groupId) {
        return metricQueryService.contextMetrics(new MetricWindow(fromMillis, toMillis, groupId));
    }

    @GetMapping("/metrics/social")
    public SocialReport socialMetrics(@RequestParam(required = false) Long fromMillis,
                                      @RequestParam(required = false) Long toMillis,
                                      @RequestParam(required = false) String groupId) {
        return metricQueryService.socialMetrics(new MetricWindow(fromMillis, toMillis, groupId));
    }

    @GetMapping("/metrics/feedback")
    public FeedbackReport interactionFeedback(@RequestParam(required = false) Long fromMillis,
                                              @RequestParam(required = false) Long toMillis,
                                              @RequestParam(required = false) String groupId) {
        return metricQueryService.interactionFeedback(new MetricWindow(fromMillis, toMillis, groupId));
    }

    @GetMapping("/metrics/runtime")
    public RuntimeReport runtime() {
        return metricQueryService.runtime();
    }
}
