package cn.edu.scut.chatbot.security.gatepolicy;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * blacklist 表 DO：用户黑名单（群级 / 全局两级）。L0 规则闸门消费——
 * 黑名单用户的消息直接忽略绝不回应（被 @ 也不回）。
 */
@Data
public class BlacklistDO {

    /**
     * global 全局 / group 群级（群级必填三元组）
     */
    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_GROUP = "group";

    private Long id;

    private String scope;

    private String platform;

    private String botId;

    private String groupId;

    private String userId;

    /**
     * 拉黑理由（管理面可读）
     */
    private String reason;

    private LocalDateTime createdAt;
}
