package cn.edu.scut.chatbot.security.audit;

/**
 * mandateDenied 按用户计数行（越权尝试集中告警探针的 Mapper 结果——
 * 取窗口内计数最高的一位，userId 即抑制维度）。
 */
public class UserDeniedCount {

    private String userId;
    private long count;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
