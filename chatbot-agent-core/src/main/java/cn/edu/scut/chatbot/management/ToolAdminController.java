package cn.edu.scut.chatbot.management;

import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 工具管理 REST（A4）：内置工具只读 descriptor/启停/调参 + HTTP 泛化工具五组字段与版本化发布回滚。
 */
@RestController
@RequestMapping("/api/management/tools")
public class ToolAdminController {

    private final ToolAdminFacades toolAdmin;

    public ToolAdminController(ToolAdminFacades toolAdmin) {
        this.toolAdmin = toolAdmin;
    }

    @GetMapping("/builtin")
    public List<ToolAdminFacades.BuiltinToolView> listBuiltin() {
        return toolAdmin.listBuiltinTools();
    }

    @PutMapping("/builtin/{toolName}")
    @AuditAction(target = "tool_config", action = "save_draft", summary = "保存内置工具草稿")
    public ToolAdminFacades.DraftSaveResult saveBuiltinDraft(
            @PathVariable String toolName,
            @RequestBody ToolAdminFacades.BuiltinToolUpdateRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("toolName", toolName));
        return toolAdmin.saveBuiltinDraft(toolName, request);
    }

    @PostMapping("/builtin/{toolName}/publish")
    @AuditAction(target = "tool_config", action = "publish", summary = "发布内置工具配置")
    public void publishBuiltin(
            @PathVariable String toolName,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("toolName", toolName));
        toolAdmin.publishBuiltin(toolName, request != null ? request.overrideReason() : null);
    }

    @PostMapping("/builtin/{toolName}/rollback")
    @AuditAction(target = "tool_config", action = "rollback", summary = "回滚内置工具配置")
    public void rollbackBuiltin(
            @PathVariable String toolName,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "toolName", toolName, "targetVersion", request.targetVersion()));
        toolAdmin.rollbackBuiltin(toolName, request.targetVersion(), request.overrideReason());
    }

    @GetMapping("/builtin/{toolName}/versions")
    public List<ToolAdminFacades.VersionItemView> builtinVersions(@PathVariable String toolName) {
        return toolAdmin.builtinVersions(toolName);
    }

    @GetMapping("/http")
    public List<ToolAdminFacades.HttpToolView> listHttp() {
        return toolAdmin.listHttpTools();
    }

    @PostMapping("/http")
    @AuditAction(target = "http_tool_config", action = "create_draft", summary = "新建 HTTP 泛化工具草稿")
    public ToolAdminFacades.DraftSaveResult createHttpDraft(
            @RequestBody ToolAdminFacades.HttpToolUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("toolName", request.toolName()));
        return toolAdmin.saveHttpToolDraft(request.toolName(), request);
    }

    @PutMapping("/http/{toolName}")
    @AuditAction(target = "http_tool_config", action = "save_draft", summary = "更新 HTTP 泛化工具草稿")
    public ToolAdminFacades.DraftSaveResult saveHttpDraft(
            @PathVariable String toolName,
            @RequestBody ToolAdminFacades.HttpToolUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("toolName", toolName));
        return toolAdmin.saveHttpToolDraft(toolName, request);
    }

    @PostMapping("/http/{toolName}/publish")
    @AuditAction(target = "http_tool_config", action = "publish", summary = "发布 HTTP 泛化工具")
    public void publishHttp(
            @PathVariable String toolName,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("toolName", toolName));
        toolAdmin.publishHttpTool(toolName, request != null ? request.overrideReason() : null);
    }

    @PostMapping("/http/{toolName}/rollback")
    @AuditAction(target = "http_tool_config", action = "rollback", summary = "回滚 HTTP 泛化工具")
    public void rollbackHttp(
            @PathVariable String toolName,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "toolName", toolName, "targetVersion", request.targetVersion()));
        toolAdmin.rollbackHttpTool(toolName, request.targetVersion(), request.overrideReason());
    }

    @GetMapping("/http/{toolName}/versions")
    public List<ToolAdminFacades.VersionItemView> httpVersions(@PathVariable String toolName) {
        return toolAdmin.httpToolVersions(toolName);
    }

    @DeleteMapping("/http/{toolName}")
    @AuditAction(target = "http_tool_config", action = "delete", summary = "删除 HTTP 泛化工具")
    public void deleteHttp(@PathVariable String toolName) {
        toolAdmin.deleteHttpTool(toolName);
    }

    public record PublishRequest(String overrideReason) {
    }

    public record RollbackRequest(int targetVersion, String overrideReason) {
    }
}
