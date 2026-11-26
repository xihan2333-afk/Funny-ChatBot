package cn.edu.scut.chatbot.observability.alert;

/**
 * 探针取样结果：value 供阈值比较，summary 是命中时写进通知的人话描述，
 * dimensionKey 是抑制维度（如越权告警的 userId、凭证告警的 provider——同规则
 * 不同维度各自计数与抑制），traceId 可空（能关联链路就带）。
 */
public record AlertSample(double value,
                          String summary,
                          String dimensionKey,
                          String traceId) {
}
