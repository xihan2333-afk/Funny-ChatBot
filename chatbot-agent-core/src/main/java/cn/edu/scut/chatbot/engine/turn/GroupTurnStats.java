package cn.edu.scut.chatbot.engine.turn;

import lombok.Data;

/**
 * 按群聚合的近 24 小时 Turn 计数与成功数投影
 */
@Data
public class GroupTurnStats {
    private String platform;
    private String botId;
    private String groupId;
    private long totalCount;
    private long successCount;
}
