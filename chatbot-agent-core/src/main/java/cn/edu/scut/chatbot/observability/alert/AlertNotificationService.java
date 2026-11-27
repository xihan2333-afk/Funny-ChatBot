package cn.edu.scut.chatbot.observability.alert;

import java.util.List;

/**
 * 告警通知管理（observe-query 契约第 2 节）：已读管理与通知中心列表消费。
 */
public interface AlertNotificationService {

    void acknowledge(long notificationId);

    List<AlertNotificationView> list(String level, Boolean unreadOnly, int page, int size);
}
