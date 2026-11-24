package cn.edu.scut.chatbot.management;

import java.util.List;

/**
 * 闸门推进门面（契约 engine-commands.md）。
 */
public interface EngineGateCommands {

    /**
     * L0/L1 决策回填（事实）：decision/decision_layer/decision_reason/batch_digest 写回触发器行。
     */
    void recordDecision(long triggerId, String decision, String decisionLayer,
                        String decisionReason, String batchDigestJson);

    /**
     * L0 升级（请求）：把待评估触发器入 assessment Effect（Effects 队列驱动 L1 批量评估）。
     */
    void escalateToL1(List<Long> triggerIds);
}
