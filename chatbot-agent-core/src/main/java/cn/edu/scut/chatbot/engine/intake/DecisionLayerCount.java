package cn.edu.scut.chatbot.engine.intake;

/**
 * trigger_event 按决策层 × 决策结果计数行（L1 通过率 / 保守降级率 / 主动开口成功率的
 * Mapper 结果）。decisionLayer 值域 L0 / L1 / degrade；decision 值域 pending /
 * accept / ignore。
 */
public class DecisionLayerCount {

    private String decisionLayer;
    private String decision;
    private long count;

    public String getDecisionLayer() {
        return decisionLayer;
    }

    public void setDecisionLayer(String decisionLayer) {
        this.decisionLayer = decisionLayer;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
