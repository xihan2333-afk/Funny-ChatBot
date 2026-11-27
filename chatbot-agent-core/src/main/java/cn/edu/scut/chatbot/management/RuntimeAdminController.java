package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.memory.emotion.EmotionAdminService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 运行时监控与受控干预 REST（A6）：运行总览、Turn 只读查询、Task 查询/取消/重试、Decision 检索、情绪监控与重置。
 */
@RestController
@RequestMapping("/api/management/runtime")
public class RuntimeAdminController {

    private final EngineCommandFacades.EngineRuntimeAdminFacade runtimeFacade;
    private final EngineCommandFacades.EngineTaskCommands taskCommands;
    private final EmotionAdminService emotionAdminService;

    public RuntimeAdminController(EngineCommandFacades.EngineRuntimeAdminFacade runtimeFacade,
                                  EngineCommandFacades.EngineTaskCommands taskCommands,
                                  EmotionAdminService emotionAdminService) {
        this.runtimeFacade = runtimeFacade;
        this.taskCommands = taskCommands;
        this.emotionAdminService = emotionAdminService;
    }

    @GetMapping("/overview")
    public EngineViews.RuntimeOverviewView overview() {
        return runtimeFacade.runtimeOverview();
    }

    @GetMapping("/turns")
    public PageResult<EngineViews.TurnSummaryView> listTurns(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return runtimeFacade.listTurns(platform, botId, groupId, status, from, to, page, pageSize);
    }

    @GetMapping("/turns/{turnId}")
    public EngineViews.TurnDetailView getTurn(@PathVariable long turnId) {
        return runtimeFacade.getTurnDetail(turnId);
    }

    @GetMapping("/tasks")
    public PageResult<EngineViews.TaskSummaryView> listTasks(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mandateSource,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return runtimeFacade.listTasks(platform, botId, groupId, status, mandateSource, category, page, pageSize);
    }

    @GetMapping("/tasks/{taskId}")
    public EngineViews.TaskDetailView getTask(@PathVariable long taskId) {
        return runtimeFacade.getTaskDetail(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @AuditAction(target = "task", action = "cancel", summary = "管理员取消运行中任务")
    public void cancelTask(
            @PathVariable long taskId,
            @RequestBody(required = false) CancelTaskRequest request,
            HttpServletRequest httpRequest) {
        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason() : "管理员手动取消";
        taskCommands.stopTask(taskId, reason);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "taskId", taskId, "reason", reason));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @AuditAction(target = "task", action = "retry", summary = "管理员重试失败任务")
    public void retryTask(
            @PathVariable long taskId,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator,
            HttpServletRequest httpRequest) {
        runtimeFacade.retryFailedTask(taskId, operator);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("taskId", taskId));
    }

    @GetMapping("/decisions")
    public PageResult<EngineViews.DecisionItemView> listDecisions(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String decisionLayer,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return runtimeFacade.listDecisions(platform, botId, groupId, decision, decisionLayer, from, to, page, pageSize);
    }

    @GetMapping("/emotions")
    public List<EmotionAdminService.GroupEmotionView> listEmotions() {
        return emotionAdminService.listGroupEmotions();
    }

    @PostMapping("/emotions/{platform}/{botId}/{groupId}/reset")
    @AuditAction(target = "emotion", action = "reset", summary = "重置群情绪回人格基线")
    public void resetEmotion(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            HttpServletRequest httpRequest) {
        emotionAdminService.resetToBaseline(platform, botId, groupId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", platform, "botId", botId, "groupId", groupId));
    }

    public record CancelTaskRequest(String reason) {
    }
}
