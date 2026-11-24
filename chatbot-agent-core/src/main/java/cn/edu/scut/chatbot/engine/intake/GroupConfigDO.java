package cn.edu.scut.chatbot.engine.intake;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * group_config 表 DO：群级运营配置（机制在入口策略可运营——黑名单、授权清单、工具开关是数据）。
 */
@Data
public class GroupConfigDO {

    private Long id;

    private String platform;

    private String botId;

    private String groupId;

    /**
     * 用户黑名单 JSON 数组（L0 强制忽略）
     */
    private String userBlacklist;

    /**
     * member 类 Task 的允许类别清单 JSON 数组
     */
    private String allowedMemberTaskCategories;

    /**
     * autonomous 类 Task 的允许类别清单 JSON 数组
     */
    private String allowedAutonomousCategories;

    /**
     * 群级禁用工具名列表 JSON 数组（装配第三步）
     */
    private String disabledTools;

    /**
     * 群级禁用技能名列表 JSON 数组（技能两级开关的第二级——目录渲染过滤）
     */
    private String disabledSkills;

    /**
     * 群规（prompt 群上下文层素材）
     */
    private String groupRules;

    /**
     * 销毁时会话历史沉淀策略：keep 保留 / discard 丢弃
     */
    private String transcriptRetainPolicy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
