package cn.edu.scut.chatbot.memory.profile;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * group_profile 表 DO（data-model §1.4）：群体画像——每日统计纯计算刷新，零模型调用。
 * active_members / topic_dist 同时是 Prompt 群上下文层「成员概况」变量的数据源
 * （粗粒度不随发言人变化）；active_hours 供主动开口的活跃时段判定。
 * JSON 列按全库惯例映射 String、服务层解析。
 */
@Data
public class GroupProfileDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * 活跃时段分布 JSON（如 {"0":3,"14":12,...} 按小时的计数）
     */
    private String activeHours;

    /**
     * 氛围基线：热闹 / 安静 / 爱抬杠
     */
    private String vibeBaseline;

    /**
     * 话题分布 JSON（话题 → 计数）
     */
    private String topicDist;

    /**
     * 活跃成员概况 JSON（供群上下文层渲染）
     */
    private String activeMembers;

    private LocalDateTime updatedAt;
}
