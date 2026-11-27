package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 质量报表：Turn 成功率（非 FAILED 占比）、沉默比例（SILENT 占比）、L1 通过率
 * （decision_layer=L1 且 decision=accept）与保守降级率（decision_layer=degrade）、
 * 工具失败率（ES tool.* Span 的 chatbot.is_error 占比）、缓存命中占比
 * （cached_tokens / input_tokens，llm_call_log 口径）。比率样本数为 0 时为 null。
 */
public record QualityReport(long turnTotal,
                            Double turnSuccessRate,
                            Double silentRate,
                            long l1Total,
                            Double l1AcceptRate,
                            Double l1ConservativeDegradeRate,
                            long toolTotal,
                            Double toolFailureRate,
                            Double cacheHitRate) {
}
