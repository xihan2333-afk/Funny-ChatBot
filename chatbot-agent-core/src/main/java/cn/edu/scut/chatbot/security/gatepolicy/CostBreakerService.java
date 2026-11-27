package cn.edu.scut.chatbot.security.gatepolicy;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 成本熔断（security-gate 契约第 5 节，FR-017）：硬闸不是告警——当日累计消费超阈值
 * 后落 Redis 熔断标记，拒新重任务、停主动行为、拒图像/语音能力调用，保留 chat_main
 * 被动回应。次日零点自然过期恢复或管理面手动解除，当日熔断不自动解除防阈值抖动。
 */
public interface CostBreakerService {

    String SCOPE_GLOBAL = "global";

    /**
     * 拼群级熔断口径（与 llm_call_log 按 GROUP 聚合的 dimensionKey 同形）
     */
    static String groupScope(String platform, String botId, String groupId) {
        return platform + ":" + botId + ":" + groupId;
    }

    /**
     * 指定口径当前是否处于熔断态（global 或 platform:botId:groupId）
     */
    boolean isTripped(String scope);

    /**
     * createTask 校验点：熔断中（global 或本群）抛出 CommandRejectedException 拒新重任务
     */
    void onHeavyTaskAttempt(String platform, String botId, String groupId, String userId);

    /**
     * image / voice 等能力类调用前校验：熔断中拒绝（chat_main 不查，保留被动回应）
     */
    void onAbilityCall(String slotCode);

    /**
     * 群级感知的能力类调用校验（global 或本群任一熔断即拒绝）
     */
    default void onAbilityCall(String slotCode, String platform, String botId, String groupId) {
        onAbilityCall(slotCode);
    }

    /**
     * 管理面手动解除熔断（默认解除 global；动作进 security_event(circuitBreak) 审计）
     */
    void manualRelease(String operator);

    /**
     * 管理面按指定口径解除熔断（global 或群口径）
     */
    default void manualRelease(String scope, String operator) {
        manualRelease(operator);
    }

    /**
     * 分钟级定时聚合：按 llm_call_log(is_replay=0) 当日累计与阈值比对，
     * 超限即落熔断标记并写 security_event(circuitBreak)
     */
    BreakerSnapshot aggregateDaily();

    /**
     * 熔断状态快照（管理面查询与单测断言用）
     */
    record BreakerSnapshot(
            String date,
            BigDecimal globalUsage,
            BigDecimal globalLimit,
            boolean globalTripped,
            Map<String, BigDecimal> groupUsages,
            BigDecimal groupLimit,
            Map<String, Boolean> trippedGroups
    ) {
    }
}
