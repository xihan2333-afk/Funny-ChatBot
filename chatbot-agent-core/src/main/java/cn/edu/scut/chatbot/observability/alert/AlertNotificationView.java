package cn.edu.scut.chatbot.observability.alert;

/**
 * 告警通知对外视图（通知中心列表消费；management 不见 DO）。
 */
public record AlertNotificationView(long id,
                                    long ruleId,
                                    String ruleName,
                                    String level,
                                    String summary,
                                    String traceId,
                                    boolean readFlag,
                                    String createdAtIso) {

    static AlertNotificationView from(AlertNotificationDO notification) {
        return new AlertNotificationView(notification.getId(), notification.getRuleId(),
                notification.getRuleName(), notification.getLevel(), notification.getSummary(),
                notification.getTraceId(), Boolean.TRUE.equals(notification.getReadFlag()),
                notification.getCreatedAt() == null ? null : notification.getCreatedAt().toString());
    }
}
