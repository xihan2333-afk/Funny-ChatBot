package cn.edu.scut.chatbot.observability.alert;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * alert_notification 表 Mapper：命中落库、抑制判定查窗口、通知中心列表与已读。
 */
@Mapper
public interface AlertNotificationMapper {

    int insert(AlertNotificationDO notification);

    /**
     * 规则近期通知（抑制判定：窗口内同维度不重复）
     */
    List<AlertNotificationDO> selectRecentByRule(@Param("ruleId") long ruleId,
                                                 @Param("since") LocalDateTime since);

    /**
     * 通知中心列表（级别优先、时间倒序；级别与未读可过滤）
     */
    List<AlertNotificationDO> selectPage(@Param("level") String level,
                                         @Param("unreadOnly") Boolean unreadOnly,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    long countPage(@Param("level") String level, @Param("unreadOnly") Boolean unreadOnly);

    int markRead(@Param("id") long id);
}
