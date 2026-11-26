package cn.edu.scut.chatbot.llm.slot;

/**
 * 各供应商可用 key 计数行（凭证全冷却告警探针的 Mapper 结果）。
 */
public class OwnerAvailableCount {

    private String ownerCode;
    private long availableCount;

    public String getOwnerCode() {
        return ownerCode;
    }

    public void setOwnerCode(String ownerCode) {
        this.ownerCode = ownerCode;
    }

    public long getAvailableCount() {
        return availableCount;
    }

    public void setAvailableCount(long availableCount) {
        this.availableCount = availableCount;
    }
}
