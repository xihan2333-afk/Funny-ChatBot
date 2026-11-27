package cn.edu.scut.chatbot.observability.alert;

import java.time.Instant;
import java.util.Optional;

/**
 * 内置 metric 一探针（observe-query 契约第 2 节）：metric() 标识路由键，
 * sample 按规则的统计窗口取数。探针只取样不判定——阈值比较与抑制归 AlertEngine，
 * 十二条内置规则各一个实现（T029）。
 *
 * <p>取数源：MySQL（llm_call_log / security_event / trigger_event / credential）、
 * ES（Span 聚合）、内存计数器（ExportHealthMetrics）。
 */
public interface MetricProbe {

    /**
     * 本探针承接的 metric 标识（如 slot.missing、turn.failRate、breaker.tripped）
     */
    String metric();

    /**
     * 按规则窗口取样；Optional.empty 表示窗口内无数据（无从判定，不算命中）
     */
    Optional<AlertSample> sample(AlertRuleDO rule, Instant now);
}
