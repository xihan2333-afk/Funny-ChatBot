package cn.edu.scut.chatbot.contract.in;

import cn.edu.scut.chatbot.contract.envelope.EnvelopePayload;

/**
 * 互动事件载荷（data-model.md 第 4 节）。OneBot notice.notify 系列 / request 系列 / 撤回 / 上传的收敛点。
 *
 * <p>subType 取值：poke / friend_request / group_join_request / message_recall / file_upload /
 * red_packet_lucky / honor / reaction / essence / other。
 *
 * @param subType        互动子类型短码，非空
 * @param targetUserId   互动目标用户，可空（戳一戳的目标、被撤回消息的主人）
 * @param operatorUserId 操作发起者，可空
 * @param requestFlag    平台处理请求的 API 凭据，可空（同步保留在 extension 的 ext.qq.request_flag）
 */
public record InteractionPayload(
        String subType,
        String targetUserId,
        String operatorUserId,
        String requestFlag
) implements EnvelopePayload {

    // ===== subType 短码（data-model.md 第 4 节 Interaction 行） =====
    /**
     * 戳一戳（OneBot notice.notify.poke）
     */
    public static final String SUB_POKE = "poke";
    /**
     * 好友请求（request.friend）
     */
    public static final String SUB_FRIEND_REQUEST = "friend_request";
    /**
     * 加群请求（request.group）
     */
    public static final String SUB_GROUP_JOIN_REQUEST = "group_join_request";
    /**
     * 消息撤回（group_recall / friend_recall）
     */
    public static final String SUB_MESSAGE_RECALL = "message_recall";
    /**
     * 群文件上传（group_upload）
     */
    public static final String SUB_FILE_UPLOAD = "file_upload";
    /**
     * 红包运（notify.lucky_king）
     */
    public static final String SUB_RED_PACKET_LUCKY = "red_packet_lucky";
    /**
     * 群荣誉（notify.honor）
     */
    public static final String SUB_HONOR = "honor";
    /**
     * 消息表情回应（NapCat 扩展 msg_emoji_like）
     */
    public static final String SUB_REACTION = "reaction";
    /**
     * 精华消息（NapCat 扩展 group_essence）
     */
    public static final String SUB_ESSENCE = "essence";
    /**
     * 未列举的互动
     */
    public static final String SUB_OTHER = "other";
}
