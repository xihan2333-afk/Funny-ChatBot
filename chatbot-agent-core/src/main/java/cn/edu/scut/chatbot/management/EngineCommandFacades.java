package cn.edu.scut.chatbot.management;

import java.util.List;

/**
 * 其余命令门面接口（契约 engine-commands.md 第 2 节清单——收口 T102）。
 *
 * <p>收口纪律（宪法第五条、ArchUnit 规则守卫）：本包不触达 engine 内部组件类——
 * 接口参数与返回全部用本包视图 record（EngineViews）或原始类型；实现方在 engine 包
 * （依赖方向：engine 实现 management 接口）。Controller 归 2.7 feature。
 */
public final class EngineCommandFacades {

    private EngineCommandFacades() {
    }

    /**
     * Turn 生命周期
     */
    public interface EngineTurnCommands {
        long acceptTurn(String platform, String botId, String groupId, long triggerId);

        void generationCompleted(long turnId, List<Long> speechOutboxIds);

        void generationFailed(long turnId, String errorCode);

        void dropTurn(long turnId, String reason);
    }

    /**
     * Task 生命周期（创建参数用 EngineViews.TaskCreation）
     */
    public interface EngineTaskCommands {
        long createTask(EngineViews.TaskCreation creation);

        void advanceTask(long taskId);

        void stopTask(long taskId, String reason);

        void taskProgress(long taskId, String note);

        void taskConcluded(long taskId, String resultJson, String errorCode);

        int revalidateMandates(String platform, String botId, String groupId);
    }

    /**
     * 会话与推理权（票据对 management 是不透明凭据）
     */
    public interface EngineSessionCommands {
        Object acquireInference(String platform, String botId, String groupId, String purpose);

        void releaseInference(Object ticket);
    }

    /**
     * 出站
     */
    public interface EngineOutboxCommands {
        long enqueueOutbound(String platform, String botId, String groupId, String type,
                             String payloadJson, Long turnId, Long taskId);

        void outboxSettled(long outboxId, String status, String reportJson, String errorCode);
    }

    /**
     * 群级生命周期
     */
    public interface EngineLifecycleCommands {
        void discoverGroup(String platform, String botId, String groupId, String groupName);

        void pauseGroup(String platform, String botId, String groupId);

        void resumeGroup(String platform, String botId, String groupId);

        void destroyGroup(String platform, String botId, String groupId);
    }

    /**
     * 任务查询
     */
    public interface EngineTaskQueries {
        List<EngineViews.TaskView> queryTasks(String platform, String botId, String groupId);

        EngineViews.TaskView queryTask(long taskId);
    }

    /**
     * 决策查询
     */
    public interface EngineDecisionQueries {
        List<EngineViews.TriggerEventView> queryDecisions(String platform, String botId, String groupId);

        EngineViews.TriggerEventView queryDecision(long triggerId);
    }

    /**
     * 会话查询
     */
    public interface EngineSessionQueries {
        EngineViews.SessionView querySession(String platform, String botId, String groupId);

        List<EngineViews.TranscriptEntryView> queryTranscript(long sessionId);
    }

    /**
     * 总览只读查询（各群近 24 小时 Turn 统计与基础健康状态）
     */
    public interface EngineOverviewQueries {
        List<EngineViews.GroupHealthView> queryGroupHealthLast24h();
    }

    /**
     * 群管理与六块配置门面（A2/A6/A7）
     */
    public interface EngineGroupAdminFacade {
        PageResult<EngineViews.GroupListItemView> listGroups(
                String platform, String botId, String lifecycle, Boolean inService, String keyword, int page, int pageSize);

        void setInService(String platform, String botId, String groupId, boolean inService);

        EngineViews.GroupLifecyclePrecheckView precheckLifecycle(String platform, String botId, String groupId);

        void updateTranscriptRetainPolicy(String platform, String botId, String groupId, boolean retainTranscriptToMemory);

        EngineViews.GroupConfigView getGroupConfig(String platform, String botId, String groupId);

        EngineViews.MandatePrecheckResult precheckMandate(
                String platform, String botId, String groupId, EngineViews.GroupConfigUpdateRequest request);

        EngineViews.GroupConfigSaveResult saveGroupConfig(
                String platform, String botId, String groupId, EngineViews.GroupConfigUpdateRequest request, String operator);

        PageResult<EngineViews.GroupPersonaHistoryView> listPersonaHistory(
                String platform, String botId, String groupId, int page, int pageSize);
    }

    /**
     * 运行时监控与受控干预门面（A6）
     */
    public interface EngineRuntimeAdminFacade {
        EngineViews.RuntimeOverviewView runtimeOverview();

        PageResult<EngineViews.TurnSummaryView> listTurns(
                String platform, String botId, String groupId, String status,
                String fromIso, String toIso, int page, int pageSize);

        EngineViews.TurnDetailView getTurnDetail(long turnId);

        PageResult<EngineViews.TaskSummaryView> listTasks(
                String platform, String botId, String groupId, String status,
                String mandateSource, String category, int page, int pageSize);

        EngineViews.TaskDetailView getTaskDetail(long taskId);

        void retryFailedTask(long taskId, String operator);

        PageResult<EngineViews.DecisionItemView> listDecisions(
                String platform, String botId, String groupId, String decision,
                String decisionLayer, String fromIso, String toIso, int page, int pageSize);
    }
}
