package cn.edu.scut.chatbot.management;

import java.util.List;

/**
 * 管理面视图 record（management 收口：管理面只见视图不见 engine 内部类型——宪法第五条）。
 */
public final class EngineViews {

    private EngineViews() {
    }

    /**
     * Task 创建参数（门面入参——Mandate 三要素 + 任务内容）
     */
    public record TaskCreation(
            String platform, String botId, String groupId,
            String taskKind, String category, String mandateSource,
            String mandateRef, String creatorUserId, String snapshot,
            String scheduleAtIso
    ) {
    }

    /**
     * Task 视图
     */
    public record TaskView(
            long id, String groupId, String taskKind, String category, String status,
            String mandateSource, String scheduleAtIso, String resultJson, String errorCode
    ) {
    }

    /**
     * 触发器与决策视图
     */
    public record TriggerEventView(
            long id, String groupId, String triggerKind, String eventType, String dedupKey,
            String decision, String decisionLayer, String decisionReason, Long turnId
    ) {
    }

    /**
     * 会话视图
     */
    public record SessionView(
            long id, String groupId, String lifecycle, String windowLevel,
            String emotion, Long summaryEntryId
    ) {
    }

    /**
     * 转录条目视图
     */
    public record TranscriptEntryView(
            long id, long sessionId, String kind, String payloadPreview, int weight, Long runId
    ) {
    }

    /**
     * 总览页群健康视图（近 24 小时 Turn 统计与人格绑定状态）
     */
    public record GroupHealthView(
            String platform, String botId, String groupId, String groupName,
            String lifecycle, boolean inService, boolean hasPersonaBound,
            long turnCount24h, double turnSuccessRate24h
    ) {
    }

    /**
     * 群列表行视图
     */
    public record GroupListItemView(
            String platform, String botId, String groupId, String groupName,
            String lifecycle, boolean inService, long inFlightTaskCount,
            Long personaId, String personaName, String updatedAt
    ) {
    }

    /**
     * 群生命周期操作前预检视图
     */
    public record GroupLifecyclePrecheckView(
            long inFlightTaskCount, long activeWorkerCount, boolean hasSession, String transcriptRetainPolicy
    ) {
    }

    /**
     * 群人格切换历史视图
     */
    public record GroupPersonaHistoryView(
            long id, Long fromPersonaId, String fromPersonaName,
            long toPersonaId, String toPersonaName,
            String operator, String reason, String switchedAt
    ) {
    }

    /**
     * 群主动行为参数
     */
    public record ProactiveParamsView(
            Integer silenceBreakMinutes, Integer minIntervalHours,
            Integer dailyBudget, String activeHoursOverride
    ) {
    }

    /**
     * 群六块全量配置视图
     */
    public record GroupConfigView(
            String platform, String botId, String groupId, String groupName,
            Long personaId, String personaName,
            List<String> allowedMemberTaskCategories,
            List<String> allowedAutonomousCategories,
            List<String> disabledTools,
            java.util.Map<String, java.util.Map<String, Object>> toolParamOverrides,
            List<String> disabledSkills,
            ProactiveParamsView proactiveParams,
            List<String> userBlacklist,
            String groupRules,
            String transcriptRetainPolicy,
            List<GroupPersonaHistoryView> recentPersonaHistory
    ) {
    }

    /**
     * 群配置保存请求
     */
    public record GroupConfigUpdateRequest(
            Long personaId,
            String personaSwitchReason,
            List<String> allowedMemberTaskCategories,
            List<String> allowedAutonomousCategories,
            List<String> disabledTools,
            java.util.Map<String, java.util.Map<String, Object>> toolParamOverrides,
            List<String> disabledSkills,
            ProactiveParamsView proactiveParams,
            List<String> userBlacklist,
            String groupRules,
            String transcriptRetainPolicy
    ) {
    }

    /**
     * Mandate 收紧预检命中的任务摘要
     */
    public record AffectedTaskSummary(long taskId, String taskKind, String category, String mandateSource,
                                      String status) {
    }

    /**
     * Mandate 收紧预检响应
     */
    public record MandatePrecheckResult(int affectedTaskCount, List<AffectedTaskSummary> affectedTasks) {
    }

    /**
     * 群配置保存结果
     */
    public record GroupConfigSaveResult(boolean saved, int cancelledTaskCount, GroupConfigView config) {
    }

    /**
     * 运行总览视图
     */
    public record RuntimeGroupStatusView(
            String platform, String botId, String groupId, String groupName,
            String lifecycle, boolean inService,
            long runningTurns, long runningTasks, long activeWorkers,
            int proactiveBudgetUsed, int proactiveBudgetLimit, boolean breakerTripped
    ) {
    }

    public record RuntimeOverviewView(
            boolean globalBreakerTripped,
            double todaySpendUsd,
            double dailyLimitUsd,
            List<RuntimeGroupStatusView> groups
    ) {
    }

    /**
     * Turn 列表行视图
     */
    public record TurnSummaryView(
            long id, String platform, String botId, String groupId,
            String status, String silentReason, String errorCode,
            String acceptedAt, String finishedAt
    ) {
    }

    /**
     * Outbox 回执摘要视图
     */
    public record OutboxItemView(long id, String type, String status, String receiptSummary, String errorCode) {
    }

    /**
     * Turn 详情视图（只读，含解析出的 traceId）
     */
    public record TurnDetailView(
            long id, String platform, String botId, String groupId, String status,
            String silentReason, String errorCode, String acceptedAt, String finishedAt,
            String traceId,
            List<TriggerEventView> triggers,
            List<OutboxItemView> outboxes
    ) {
    }

    /**
     * Task 分页行与详情视图
     */
    public record TaskSummaryView(
            long id, String platform, String botId, String groupId,
            String taskKind, String category, String status,
            String mandateSource, String scheduleAt, String errorCode, String createdAt
    ) {
    }

    public record TaskExecutionLogView(long id, String kind, String note, String createdAt) {
    }

    public record TaskDetailView(
            long id, String platform, String botId, String groupId,
            String taskKind, String category, String status,
            String mandateSource, String mandateRef, String creatorUserId,
            String snapshot, String scheduleAt, String result, String errorCode,
            boolean workerAlive,
            List<TaskExecutionLogView> progressLogs
    ) {
    }

    /**
     * Decision 分页行视图
     */
    public record DecisionItemView(
            long id, String platform, String botId, String groupId,
            String triggerKind, String eventType, String decision,
            String decisionLayer, String decisionReason, String batchDigest,
            Long turnId, String occurredAt, String decidedAt
    ) {
    }
}
