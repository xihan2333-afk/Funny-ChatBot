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
}
