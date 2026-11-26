package cn.edu.scut.chatbot.infrastructure.es;

import java.util.Map;

/**
 * 上下文报表：投影量（窗口组装进 Prompt 的规模——以 llm_call_log 输入 token 均值为口径，
 * 真实投影明细在 Span 与转录，第一版取可用事实源）、压缩频率（transcript_entry 的
 * summary 条目计数——004 摘要压缩落转录）、水位档位分布（agent_session.window_level 计数）。
 */
public record ContextReport(double avgProjectionTokens,
                            long summaryCount,
                            Map<String, Long> waterLevelDistribution) {
}
