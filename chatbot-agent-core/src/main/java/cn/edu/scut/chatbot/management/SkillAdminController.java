package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.skill.SkillAdminService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Skill 管理 REST（A4）：列表含引用群数、200 字校验、渲染预览、统一发布与回滚。
 */
@RestController
@RequestMapping("/api/management/skills")
public class SkillAdminController {

    private final SkillAdminService skillAdminService;

    public SkillAdminController(SkillAdminService skillAdminService) {
        this.skillAdminService = skillAdminService;
    }

    @GetMapping
    public PageResult<SkillAdminService.SkillSummaryView> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return skillAdminService.listSkills(page, pageSize);
    }

    @GetMapping("/{name}")
    public SkillAdminService.SkillDetailView get(@PathVariable String name) {
        return skillAdminService.getSkill(name);
    }

    @PostMapping
    @AuditAction(target = "skill", action = "create_draft", summary = "新建 Skill 草稿")
    public SkillAdminService.DraftResult create(
            @RequestBody SkillAdminService.SkillUpsertRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("name", request.name()));
        return skillAdminService.saveDraft(request.name(), request, operator);
    }

    @PutMapping("/{name}")
    @AuditAction(target = "skill", action = "save_draft", summary = "保存 Skill 草稿")
    public SkillAdminService.DraftResult update(
            @PathVariable String name,
            @RequestBody SkillAdminService.SkillUpsertRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("name", name));
        return skillAdminService.saveDraft(name, request, operator);
    }

    @PostMapping("/{name}/preview")
    @SkipAutoAudit
    public SkillAdminService.PreviewView preview(@PathVariable String name) {
        return skillAdminService.preview(name);
    }

    @PostMapping("/{name}/publish")
    @AuditAction(target = "skill", action = "publish", summary = "发布 Skill")
    public void publish(
            @PathVariable String name,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("name", name));
        skillAdminService.publish(name, request != null ? request.overrideReason() : null);
    }

    @PostMapping("/{name}/rollback")
    @AuditAction(target = "skill", action = "rollback", summary = "回滚 Skill")
    public void rollback(
            @PathVariable String name,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "name", name, "targetVersion", request.targetVersion()));
        skillAdminService.rollback(name, request.targetVersion());
    }

    @GetMapping("/{name}/versions")
    public List<SkillAdminService.VersionView> versions(@PathVariable String name) {
        return skillAdminService.versions(name);
    }

    @PutMapping("/{name}/enabled")
    @AuditAction(target = "skill", action = "set_enabled", summary = "启停 Skill")
    public void setEnabled(
            @PathVariable String name,
            @RequestParam boolean enabled,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        skillAdminService.setEnabled(name, enabled, operator);
    }

    @DeleteMapping("/{name}")
    @AuditAction(target = "skill", action = "delete", summary = "删除 Skill")
    public void delete(
            @PathVariable String name,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        skillAdminService.delete(name, operator);
    }

    public record PublishRequest(String overrideReason) {
    }

    public record RollbackRequest(int targetVersion) {
    }
}
