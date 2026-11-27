package cn.edu.scut.chatbot.management;

import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 群列表、服务名单、生命周期命令与六块群配置管理 REST（A2/A6/A7）。
 * <p>严格遵守“群实体由入事件流自动发现，不提供手动新增群号接口”纪律。
 */
@RestController
@RequestMapping("/api/management/groups")
public class GroupAdminController {

    private final EngineCommandFacades.EngineGroupAdminFacade groupAdminFacade;
    private final EngineCommandFacades.EngineLifecycleCommands lifecycleCommands;

    public GroupAdminController(EngineCommandFacades.EngineGroupAdminFacade groupAdminFacade,
                                EngineCommandFacades.EngineLifecycleCommands lifecycleCommands) {
        this.groupAdminFacade = groupAdminFacade;
        this.lifecycleCommands = lifecycleCommands;
    }

    @GetMapping
    public PageResult<EngineViews.GroupListItemView> list(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) Boolean inService,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return groupAdminFacade.listGroups(platform, botId, lifecycle, inService, keyword, page, pageSize);
    }

    @PutMapping("/{platform}/{botId}/{groupId}/service")
    @AuditAction(target = "group", action = "set_in_service", summary = "切换群服务名单开关")
    public Map<String, Object> setInService(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            @RequestBody InServiceRequest request,
            HttpServletRequest httpRequest) {
        boolean inService = request != null && request.inService();
        groupAdminFacade.setInService(platform, botId, groupId, inService);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", platform, "botId", botId, "groupId", groupId, "inService", inService
        ));
        return Map.of("groupId", groupId, "inService", inService);
    }

    @GetMapping("/{platform}/{botId}/{groupId}/lifecycle/precheck")
    public EngineViews.GroupLifecyclePrecheckView precheckLifecycle(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId) {
        return groupAdminFacade.precheckLifecycle(platform, botId, groupId);
    }

    @PostMapping("/{platform}/{botId}/{groupId}/pause")
    @AuditAction(target = "group", action = "pause", summary = "暂停群服务")
    public Map<String, Object> pause(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            HttpServletRequest httpRequest) {
        lifecycleCommands.pauseGroup(platform, botId, groupId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", platform, "botId", botId, "groupId", groupId
        ));
        return Map.of("groupId", groupId, "lifecycle", "PAUSED");
    }

    @PostMapping("/{platform}/{botId}/{groupId}/resume")
    @AuditAction(target = "group", action = "resume", summary = "恢复群服务")
    public Map<String, Object> resume(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            HttpServletRequest httpRequest) {
        lifecycleCommands.resumeGroup(platform, botId, groupId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", platform, "botId", botId, "groupId", groupId
        ));
        return Map.of("groupId", groupId, "lifecycle", "ACTIVE");
    }

    @PostMapping("/{platform}/{botId}/{groupId}/destroy")
    @AuditAction(target = "group", action = "destroy", summary = "销毁群会话与任务")
    public DestroyGroupResponse destroy(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            @RequestBody DestroyGroupRequest request,
            HttpServletRequest httpRequest) {
        if (request == null || !groupId.equals(request.confirmGroupId())) {
            throw new IllegalArgumentException("销毁群是高危操作，confirmGroupId 必须与目标群号完全一致");
        }
        groupAdminFacade.updateTranscriptRetainPolicy(platform, botId, groupId, request.retainTranscriptToMemory());
        lifecycleCommands.destroyGroup(platform, botId, groupId);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", platform,
                "botId", botId,
                "groupId", groupId,
                "retainTranscriptToMemory", request.retainTranscriptToMemory()
        ));
        return new DestroyGroupResponse(
                true, groupId, request.retainTranscriptToMemory(),
                "群已销毁，触发器记录与决策历史永久保留"
        );
    }

    @GetMapping("/{platform}/{botId}/{groupId}/config")
    public EngineViews.GroupConfigView getConfig(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId) {
        return groupAdminFacade.getGroupConfig(platform, botId, groupId);
    }

    @PostMapping("/{platform}/{botId}/{groupId}/config/precheck")
    @SkipAutoAudit
    public EngineViews.MandatePrecheckResult precheckMandate(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            @RequestBody EngineViews.GroupConfigUpdateRequest request) {
        return groupAdminFacade.precheckMandate(platform, botId, groupId, request);
    }

    @PutMapping("/{platform}/{botId}/{groupId}/config")
    @AuditAction(target = "group_config", action = "update", summary = "更新群六块配置")
    public EngineViews.GroupConfigSaveResult saveConfig(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            @RequestBody EngineViews.GroupConfigUpdateRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator,
            HttpServletRequest httpRequest) {
        EngineViews.GroupConfigSaveResult result = groupAdminFacade.saveGroupConfig(
                platform, botId, groupId, request, operator);
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "platform", platform,
                "botId", botId,
                "groupId", groupId,
                "cancelledTaskCount", result.cancelledTaskCount(),
                "personaId", request.personaId() != null ? request.personaId() : "unchanged"
        ));
        return result;
    }

    @GetMapping("/{platform}/{botId}/{groupId}/persona-history")
    public PageResult<EngineViews.GroupPersonaHistoryView> personaHistory(
            @PathVariable String platform,
            @PathVariable String botId,
            @PathVariable String groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return groupAdminFacade.listPersonaHistory(platform, botId, groupId, page, pageSize);
    }

    public record InServiceRequest(boolean inService) {
    }

    public record DestroyGroupRequest(boolean retainTranscriptToMemory, String confirmGroupId) {
    }

    public record DestroyGroupResponse(
            boolean destroyed,
            String groupId,
            boolean retainTranscriptToMemory,
            String notice
    ) {
    }
}
