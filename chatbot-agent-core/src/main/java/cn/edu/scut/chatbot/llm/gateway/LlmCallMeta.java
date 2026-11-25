package cn.edu.scut.chatbot.llm.gateway;

/**
 * 观测元数据（data-model 第 11 节、research R8）：群三元组、Run 关联、场景与追踪标识，
 * 落账（llm_call_log）专用。由调用方提供——engine 经 CostAwareStreamFn 装饰器从
 * RunSpec 派生，直连调用方显式传参；观测元数据不进 loop 契约（内核不被观测需求污染）。
 */
public record LlmCallMeta(
        String platform,
        String botId,
        String groupId,
        Long runId,
        String runKind,
        String traceId,
        boolean replay
) {

    /**
     * 无群后台任务的空元数据（runKind 仍建议给出——落账按场景钻取）
     */
    public static LlmCallMeta of(String runKind, String traceId) {
        return new LlmCallMeta(null, null, null, null, runKind, traceId, false);
    }
}
