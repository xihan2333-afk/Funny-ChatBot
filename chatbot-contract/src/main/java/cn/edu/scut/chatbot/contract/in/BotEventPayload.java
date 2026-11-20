package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * Bot 自身事件载荷（data-model.md 第 4 节）。「受影响者是 bot 自己」的事件收敛点。
 *
 * <p>subType 取值：bot_kicked / bot_muted / bot_unmuted / friend_added / bot_offline /
 * quota_notice（官方平台演进项）/ other。
 *
 * @param subType         Bot 事件子类型短码，非空
 * @param operatorUserId  操作发起者，可空（如踢 bot 的人）
 * @param durationSeconds 禁言时长秒数，可空
 * @param groupId         群内事件才有群号，全局事件（friend_added 等）为 null
 */
public record BotEventPayload(
        String subType,
        String operatorUserId,
        Long durationSeconds,
        String groupId
) implements EnvelopePayload {

    // ===== subType 短码 =====
    /**
     * Bot 被踢出群（group_decrease.kick_me）
     */
    public static final String SUB_BOT_KICKED = "bot_kicked";
    /**
     * Bot 被禁言（group_ban 且 user_id==self_id 的 ban）
     */
    public static final String SUB_BOT_MUTED = "bot_muted";
    /**
     * Bot 被解除禁言（group_ban 且 user_id==self_id 的 lift_ban）
     */
    public static final String SUB_BOT_UNMUTED = "bot_unmuted";
    /**
     * 新好友（friend_add）
     */
    public static final String SUB_FRIEND_ADDED = "friend_added";
    /**
     * QQ 会话掉线（NapCat 扩展 bot_offline；WS 不断、事件停摆的提前感知信号）
     */
    public static final String SUB_BOT_OFFLINE = "bot_offline";
    /**
     * 平台额度通知（官方平台演进项）
     */
    public static final String SUB_QUOTA_NOTICE = "quota_notice";
    /**
     * 未列举的 Bot 事件
     */
    public static final String SUB_OTHER = "other";
}
