package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.engine.gate.L1AssessmentModel;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 评估剧本假件（research R9）：按剧本返回 Decision 批次，可注入持续失败
 * （考指数退避与耗尽降级）。
 */
public class ScriptedL1Assessor implements L1AssessmentModel {

    /**
     * 行为模式
     */
    public enum Mode {
        /**
         * 按剧本返回（默认：全部 ignore，可逐条定制）
         */
        SCRIPTED,
        /**
         * 持续失败（考退避与保守 ignore 降级）
         */
        ALWAYS_FAIL,
        /**
         * 按脚本前 N 次失败后成功（考重试成功路径）
         */
        FAIL_FIRST_THEN_SUCCESS
    }

    private Mode mode = Mode.SCRIPTED;

    private int failTimesRemaining = 0;

    /**
     * 定制决策表：dedupKey → (decision, reason)；未列出的 key 默认 ignore
     */
    private final List<AssessedDecision> scriptedDecisions = new ArrayList<>();

    private int callCount = 0;

    public ScriptedL1Assessor scriptAllIgnore() {
        scriptedDecisions.clear();
        mode = Mode.SCRIPTED;
        return this;
    }

    public ScriptedL1Assessor scriptAccept(String dedupKey, String reason) {
        scriptedDecisions.add(new AssessedDecision(dedupKey, "accept", reason));
        return this;
    }

    public ScriptedL1Assessor scriptIgnore(String dedupKey, String reason) {
        scriptedDecisions.add(new AssessedDecision(dedupKey, "ignore", reason));
        return this;
    }

    public ScriptedL1Assessor alwaysFail() {
        this.mode = Mode.ALWAYS_FAIL;
        return this;
    }

    public ScriptedL1Assessor failFirstThenSuccess(int failTimes) {
        this.mode = Mode.FAIL_FIRST_THEN_SUCCESS;
        this.failTimesRemaining = failTimes;
        return this;
    }

    public int callCount() {
        return callCount;
    }

    @Override
    public List<AssessedDecision> assess(String assessmentPrompt,
                                         List<TriggerDigest> batchDigests,
                                         List<String> recentMessages,
                                         String personaPreference) throws Exception {
        callCount++;
        if (mode == Mode.ALWAYS_FAIL) {
            throw new IllegalStateException("剧本注入：L1 评估持续失败");
        }
        if (mode == Mode.FAIL_FIRST_THEN_SUCCESS && failTimesRemaining > 0) {
            failTimesRemaining--;
            throw new IllegalStateException("剧本注入：L1 评估第 " + (callCount) + " 次失败");
        }
        // 未定制的触发器默认 ignore（安静忽略是正常状态——剧本默认值与系统纪律一致）
        List<AssessedDecision> results = new ArrayList<>();
        for (TriggerDigest digest : batchDigests) {
            results.add(scriptedDecisions.stream()
                    .filter(d -> d.dedupKey().equals(digest.dedupKey()))
                    .findFirst()
                    .orElse(new AssessedDecision(digest.dedupKey(), "ignore", "剧本默认忽略")));
        }
        return results;
    }
}
