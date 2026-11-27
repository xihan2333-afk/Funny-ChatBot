package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 时延报表：Turn 端到端（accepted_at → finished_at）、TTFT（llm_call_log.ttft_ms——
 * 验证关思考参数是否生效的最直接指标）、工具时延（ES tool.* Span duration 聚合）。
 */
public record LatencyReport(long turnSamples,
                            Percentiles turnEndToEnd,
                            long ttftSamples,
                            Percentiles ttft,
                            long toolSamples,
                            Percentiles tool) {
}
