package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * 群成员事件载荷（data-model.md 第 4 节）。群内「某个普通成员发生了什么」的收敛点。
 *
 * <p>归属判定规则：OneBot 事件以「受影响者是谁」归属——受影响者是普通成员进本原语，
 * 受影响者是 bot 自身（self_id 出现在 user_id / target_id）进 BotEvent。
 *
 * <p>subType 取值：member_join / member_leave / member_kicked / muted / unmuted /
 * admin_granted / admin_revoked / card_changed。
 *
 * @param subType         成员事件子类型短码，非空
 * @param operatorUserId  操作发起者，可空（如踢人的人）
 * @param durationSeconds 禁言时长秒数，可空（muted 时有值）
 */
public record MemberEventPayload(
        String subType,
        String operatorUserId,
        Long durationSeconds
) implements EnvelopePayload {

    // ===== subType 短码 =====
    /**
     * 入群（group_increase）
     */
    public static final String SUB_MEMBER_JOIN = "member_join";
    /**
     * 主动退群（group_decrease.leave）
     */
    public static final String SUB_MEMBER_LEAVE = "member_leave";
    /**
     * 被踢出群（group_decrease.kick）
     */
    public static final String SUB_MEMBER_KICKED = "member_kicked";
    /**
     * 被禁言（group_ban ban，受影响者为普通成员）
     */
    public static final String SUB_MUTED = "muted";
    /**
     * 被解除禁言（group_ban lift_ban，受影响者为普通成员）
     */
    public static final String SUB_UNMUTED = "unmuted";
    /**
     * 被授予管理员（group_admin.set）
     */
    public static final String SUB_ADMIN_GRANTED = "admin_granted";
    /**
     * 被撤销管理员（group_admin.unset）
     */
    public static final String SUB_ADMIN_REVOKED = "admin_revoked";
    /**
     * 群名片变更（NapCat 扩展 group_card）
     */
    public static final String SUB_CARD_CHANGED = "card_changed";
}
