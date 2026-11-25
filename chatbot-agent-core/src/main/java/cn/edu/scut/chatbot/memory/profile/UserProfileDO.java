package cn.edu.scut.chatbot.memory.profile;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * user_profile 表 DO（data-model §1.3）：用户画像——按群隔离（uk = 三元组 + userId）。
 * 双轴态度：familiarity 只增不衰减；affinity 存原始值读时懒衰减（7 天半衰向 0 回归）。
 */
@Data
public class UserProfileDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    private String userId;

    /**
     * 平台昵称（称呼回退源）
     */
    private String platformName;

    /**
     * 学到的称呼（优先使用）
     */
    private String callName;

    /**
     * 活跃度分档（每日统计纯计算刷新，不走模型）
     */
    private String activityLevel;

    /**
     * 兴趣标签 JSON（从偏好类记忆提炼）
     */
    private String interestTags;

    /**
     * 0~100，只随互动累积不衰减
     */
    private Integer familiarity;

    /**
     * -100~+100 存储原始值，读取时懒衰减
     */
    private Integer affinity;

    /**
     * 互动摘要（相处基调，态度的语义层）
     */
    private String interactDigest;

    private LocalDateTime updatedAt;
}
