package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 交互反馈报表（模型分 × 用户反馈对照信号，不进 Rubric——spec 2.5.6 明文）：
 * 被回复率 = Agent 发言（transcript kind=assistant）后 10 分钟内同群出现 user
 * 跟帖的发言占比（转录统计）。表情回应率：平台表情回应事件当前无落表——列演进项。
 */
public record FeedbackReport(long agentMessages,
                             long repliedMessages,
                             Double repliedRate) {
}
