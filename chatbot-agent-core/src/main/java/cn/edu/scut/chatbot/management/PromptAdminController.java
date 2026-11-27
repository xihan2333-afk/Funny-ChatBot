package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.prompt.PromptAdminService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Prompt 片段管理 REST（A4）：四层 + standalone 分组、锁定保护、变量校验与统一发布回滚。
 */
@RestController
@RequestMapping("/api/management/prompts")
public class PromptAdminController {

    private final PromptAdminService promptAdminService;

    public PromptAdminController(PromptAdminService promptAdminService) {
        this.promptAdminService = promptAdminService;
    }

    @GetMapping
    public Map<String, List<PromptAdminService.FragmentView>> listGrouped() {
        return promptAdminService.listGrouped();
    }

    @GetMapping("/{fragmentKey}")
    public PromptAdminService.FragmentView get(@PathVariable String fragmentKey) {
        return promptAdminService.getByKey(fragmentKey);
    }

    @PostMapping
    @AuditAction(target = "prompt_fragment", action = "create_draft", summary = "新建 Prompt 片段草稿")
    public PromptAdminService.DraftResult create(
            @RequestBody PromptAdminService.FragmentUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL,
                Map.of("fragmentKey", request.fragmentKey()));
        return promptAdminService.saveDraft(request.fragmentKey(), request);
    }

    @PutMapping("/{fragmentKey}")
    @AuditAction(target = "prompt_fragment", action = "save_draft", summary = "保存 Prompt 片段草稿")
    public PromptAdminService.DraftResult update(
            @PathVariable String fragmentKey,
            @RequestBody PromptAdminService.FragmentUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("fragmentKey", fragmentKey));
        return promptAdminService.saveDraft(fragmentKey, request);
    }

    @PostMapping("/{fragmentKey}/publish")
    @AuditAction(target = "prompt_fragment", action = "publish", summary = "发布 Prompt 片段")
    public void publish(
            @PathVariable String fragmentKey,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("fragmentKey", fragmentKey));
        promptAdminService.publish(fragmentKey, request != null ? request.overrideReason() : null);
    }

    @PostMapping("/{fragmentKey}/rollback")
    @AuditAction(target = "prompt_fragment", action = "rollback", summary = "回滚 Prompt 片段")
    public void rollback(
            @PathVariable String fragmentKey,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "fragmentKey", fragmentKey, "targetVersion", request.targetVersion()));
        promptAdminService.rollback(fragmentKey, request.targetVersion());
    }

    @GetMapping("/{fragmentKey}/versions")
    public List<PromptAdminService.VersionView> versions(@PathVariable String fragmentKey) {
        return promptAdminService.versions(fragmentKey);
    }

    @DeleteMapping("/{fragmentKey}")
    @AuditAction(target = "prompt_fragment", action = "delete", summary = "删除 Prompt 片段")
    public void delete(@PathVariable String fragmentKey) {
        promptAdminService.delete(fragmentKey);
    }

    public record PublishRequest(String overrideReason) {
    }

    public record RollbackRequest(int targetVersion) {
    }
}
