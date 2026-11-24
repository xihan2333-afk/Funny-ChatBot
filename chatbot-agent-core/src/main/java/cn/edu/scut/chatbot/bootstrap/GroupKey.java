package cn.edu.scut.chatbot.bootstrap;

/**
 * 群隔离三元组（宪法第四条：(platform, botId, groupId) 是数据隔离与事件路由的唯一键）。
 *
 * <p>全站以此 record 传递三元组，杜绝三个裸字符串在方法签名里漂移；
 * asMapKey 形态（platform:botId:groupId）用于日志与串行键。
 */
public record GroupKey(String platform, String botId, String groupId) {

    /**
     * 日志与串行键形态：platform:botId:groupId
     */
    public String asMapKey() {
        return platform + ":" + botId + ":" + groupId;
    }
}
