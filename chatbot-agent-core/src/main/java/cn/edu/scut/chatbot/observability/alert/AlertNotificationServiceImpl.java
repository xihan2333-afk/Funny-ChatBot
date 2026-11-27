package cn.edu.scut.chatbot.observability.alert;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知已读与列表：acknowledge 幂等（已读再标已读无副作用）。
 */
@Service
public class AlertNotificationServiceImpl implements AlertNotificationService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AlertNotificationMapper notificationMapper;

    public AlertNotificationServiceImpl(AlertNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Override
    public void acknowledge(long notificationId) {
        notificationMapper.markRead(notificationId);
    }

    @Override
    public List<AlertNotificationView> list(String level, Boolean unreadOnly, int page, int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return notificationMapper.selectPage(normalize(level), unreadOnly, bounded, page * bounded)
                .stream().map(AlertNotificationView::from).toList();
    }

    private String normalize(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        return level;
    }
}
