package cn.edu.scut.chatbot.engine.turn;

/**
 * turn 按状态计数行（质量报表的 Mapper 结果）。
 */
public class TurnStatusCount {

    private String status;
    private long count;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
