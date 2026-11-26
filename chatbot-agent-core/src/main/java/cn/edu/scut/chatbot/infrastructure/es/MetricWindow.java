package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 指标查询通用窗口：时间范围（毫秒）+ 可选群过滤（null 查全部群）。
 * 时延 / 质量 / 上下文 / 社交 / 反馈五组报表共用。
 */
public record MetricWindow(Long fromMillis,
                           Long toMillis,
                           String groupId) {
}
