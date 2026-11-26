package cn.edu.scut.chatbot.engine.session;

/**
 * agent_session 按水位档位计数行（上下文报表的 Mapper 结果）。
 */
public class WindowLevelCount {

    private String windowLevel;
    private long count;

    public String getWindowLevel() {
        return windowLevel;
    }

    public void setWindowLevel(String windowLevel) {
        this.windowLevel = windowLevel;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
