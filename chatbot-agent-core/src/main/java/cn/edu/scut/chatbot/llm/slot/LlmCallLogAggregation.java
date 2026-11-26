package cn.edu.scut.chatbot.llm.slot;

import java.math.BigDecimal;

/**
 * llm_call_log 聚合行（成本报表与缓存命中统计的 Mapper 结果）：
 * dimensionKey 由查询的聚合维度决定（群拼接 / 槽位 / 模型 / 日），全表聚合时空。
 */
public class LlmCallLogAggregation {

    private String dimensionKey;
    private long calls;
    private long inputTokens;
    private long outputTokens;
    private long cachedTokens;
    private BigDecimal costAmount;

    public String getDimensionKey() {
        return dimensionKey;
    }

    public void setDimensionKey(String dimensionKey) {
        this.dimensionKey = dimensionKey;
    }

    public long getCalls() {
        return calls;
    }

    public void setCalls(long calls) {
        this.calls = calls;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public long getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(long cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public void setCostAmount(BigDecimal costAmount) {
        this.costAmount = costAmount;
    }
}
