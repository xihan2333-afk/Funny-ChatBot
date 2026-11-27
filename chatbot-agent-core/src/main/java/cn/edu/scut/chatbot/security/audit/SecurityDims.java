package cn.edu.scut.chatbot.security.audit;

/**
 * 安全事件的隔离维度（platform, botId, groupId, userId 四段可空）：事件发生在哪个
 * 群哪个成员身上；管理操作没有群语境时全空，操作人写进 detail 载荷。
 *
 * <p>对外契约一律 record（宪法「代码可读性」节：直白构造，不用长参链构造器）。
 */
public record SecurityDims(String platform, String botId, String groupId, String userId) {

    /**
     * 无隔离维度形态（管理面与全局级事件）
     */
    public static SecurityDims none() {
        return new SecurityDims(null, null, null, null);
    }

    /**
     * 群级维度（无具体成员）
     */
    public static SecurityDims ofGroup(String platform, String botId, String groupId) {
        return new SecurityDims(platform, botId, groupId, null);
    }

    /**
     * 成员级维度
     */
    public static SecurityDims ofUser(String platform, String botId, String groupId, String userId) {
        return new SecurityDims(platform, botId, groupId, userId);
    }
}
