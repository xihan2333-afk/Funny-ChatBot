package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 指标查询（observe-query 契约第 1 节，六组口径 + runtime）：指标页经主服务
 * 转发取数。成本事实源在 MySQL（llm_call_log 为主），时延 / 工具失败率走 ES Span
 * 聚合，runtime 读 OTel 指标数据流。
 */
public interface MetricQueryService {

    CostReport cost(CostQuery query);

    LatencyReport latency(MetricWindow window);

    QualityReport quality(MetricWindow window);

    ContextReport contextMetrics(MetricWindow window);

    SocialReport socialMetrics(MetricWindow window);

    FeedbackReport interactionFeedback(MetricWindow window);

    RuntimeReport runtime();
}
