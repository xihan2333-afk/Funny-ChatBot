package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.memory.persona.PersonaAdminService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 人格库与演进提案管理 REST（A3）：七组字段 CRUD、发布回滚、绑定群与切换历史、演进提案三选一审阅与人工反馈。
 */
@RestController
@RequestMapping("/api/management/personas")
public class PersonaAdminController {

    private final PersonaAdminService personaAdminService;

    public PersonaAdminController(PersonaAdminService personaAdminService) {
        this.personaAdminService = personaAdminService;
    }

    @GetMapping
    public List<PersonaAdminService.PersonaSummaryView> list() {
        return personaAdminService.listPersonas();
    }

    @GetMapping("/{id}")
    public PersonaAdminService.PersonaDetailView get(@PathVariable long id) {
        return personaAdminService.getPersona(id);
    }

    @PostMapping
    @AuditAction(target = "persona", action = "create_draft", summary = "新建人格草稿")
    public PersonaAdminService.DraftResult create(
            @RequestBody PersonaAdminService.PersonaUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("name", request.name()));
        return personaAdminService.saveDraft(null, request);
    }

    @PutMapping("/{id}")
    @AuditAction(target = "persona", action = "save_draft", summary = "保存人格草稿")
    public PersonaAdminService.DraftResult update(
            @PathVariable long id,
            @RequestBody PersonaAdminService.PersonaUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("personaId", id));
        return personaAdminService.saveDraft(id, request);
    }

    @PostMapping("/{id}/publish")
    @AuditAction(target = "persona", action = "publish", summary = "发布人格")
    public void publish(
            @PathVariable long id,
            @RequestBody(required = false) PublishRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("personaId", id));
        personaAdminService.publish(id, request != null ? request.overrideReason() : null);
    }

    @PostMapping("/{id}/rollback")
    @AuditAction(target = "persona", action = "rollback", summary = "回滚人格")
    public void rollback(
            @PathVariable long id,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "personaId", id, "targetVersion", request.targetVersion()));
        personaAdminService.rollback(id, request.targetVersion());
    }

    @GetMapping("/{id}/versions")
    public List<PersonaAdminService.VersionView> versions(@PathVariable long id) {
        return personaAdminService.versions(id);
    }

    @GetMapping("/{id}/bindings")
    public PersonaAdminService.BindingsView bindings(@PathVariable long id) {
        return personaAdminService.bindings(id);
    }

    @GetMapping("/{id}/proposals")
    public List<PersonaAdminService.ProposalView> listProposals(
            @PathVariable long id,
            @RequestParam(required = false) String status) {
        return personaAdminService.listProposals(id, status);
    }

    @PostMapping("/{id}/evolution/trigger")
    @AuditAction(target = "persona_evolution", action = "trigger", summary = "手动触发人格演进分析")
    public void triggerEvolution(@PathVariable long id) {
        personaAdminService.triggerEvolution(id);
    }

    @PostMapping("/proposals/{proposalId}/review")
    @AuditAction(target = "persona_proposal", action = "review", summary = "审阅人格演进提案")
    public void reviewProposal(
            @PathVariable long proposalId,
            @RequestBody PersonaAdminService.ReviewRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "proposalId", proposalId, "decision", request.decision()));
        personaAdminService.reviewProposal(proposalId, request, operator);
    }

    @GetMapping("/{id}/feedback")
    public List<PersonaAdminService.FeedbackView> listFeedbacks(@PathVariable long id) {
        return personaAdminService.listFeedbacks(id);
    }

    @PostMapping("/{id}/feedback")
    @AuditAction(target = "persona_admin_feedback", action = "create", summary = "记录人格演进人工反馈")
    public PersonaAdminService.FeedbackView addFeedback(
            @PathVariable long id,
            @RequestBody PersonaAdminService.FeedbackRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "personaId", id, "tag", request.tag()));
        return personaAdminService.addFeedback(id, request, operator);
    }

    public record PublishRequest(String overrideReason) {
    }

    public record RollbackRequest(int targetVersion) {
    }
}
