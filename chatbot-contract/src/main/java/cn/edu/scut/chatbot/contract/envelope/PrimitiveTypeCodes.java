package cn.edu.scut.chatbot.contract.envelope;

/**
 * 原语类型短码：信封 type 字段的取值集合，入方向 8 种与出方向 5 种共用一个字符串空间
 * （data-model.md 第 10 节）。
 *
 * <p>线格式上它同时是多态标记——消费端凭信封顶层 type 查映射表决定 payload 按哪个 record
 * 解释（平铺多态，不使用 Jackson 默认多态注解，载荷不出现 Java 类名）。
 */
public final class PrimitiveTypeCodes {

    // ===== 入方向原语（8 种） =====
    /**
     * 群消息
     */
    public static final String GROUP_MESSAGE = "group_message";
    /**
     * 私聊消息（第一版预留：Codec 收敛为 RawEvent 保真）
     */
    public static final String PRIVATE_MESSAGE = "private_message";
    /**
     * 互动事件（戳一戳 / 好友请求 / 加群请求 / 撤回 / 文件上传 / 红包运 / 荣誉 / 表情回应 / 精华等）
     */
    public static final String INTERACTION = "interaction";
    /**
     * 成员事件（入群 / 退群 / 被踢 / 禁言 / 管理员任免 / 群名片变更）
     */
    public static final String MEMBER_EVENT = "member_event";
    /**
     * 群事件（群维度信息变更）
     */
    public static final String GROUP_EVENT = "group_event";
    /**
     * Bot 自身事件（被踢 / 被禁言 / 好友新增 / 掉线 / 额度通知）
     */
    public static final String BOT_EVENT = "bot_event";
    /**
     * 投递回执（出方向闭环专用，作为入事件回流）
     */
    public static final String DELIVERY_REPORT = "delivery_report";
    /**
     * 兜底原语：留档了但不认识，Agent 可忽略
     */
    public static final String RAW_EVENT = "raw_event";

    // ===== 出方向原语（5 种） =====
    /**
     * 发群消息
     */
    public static final String SEND_GROUP_MESSAGE = "send_group_message";
    /**
     * 发私聊消息（第一版预留：Codec 拒绝并回执 unsupported）
     */
    public static final String SEND_PRIVATE_MESSAGE = "send_private_message";
    /**
     * 撤回消息
     */
    public static final String RECALL_MESSAGE = "recall_message";
    /**
     * 设置表情回应
     */
    public static final String SET_REACTION = "set_reaction";
    /**
     * 处理互动请求（好友 / 加群请求的同意或拒绝）
     */
    public static final String SEND_INTERACTION_REPLY = "send_interaction_reply";

    private PrimitiveTypeCodes() {
    }
}
