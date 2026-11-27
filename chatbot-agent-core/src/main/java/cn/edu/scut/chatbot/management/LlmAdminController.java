package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.llm.slot.LlmAdminService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 模型供应商、模型清单、九槽位、凭证池与重嵌入迁移管理 REST（A4）。
 */
@RestController
@RequestMapping("/api/management/llm")
public class LlmAdminController {

    private final LlmAdminService llmAdminService;

    public LlmAdminController(LlmAdminService llmAdminService) {
        this.llmAdminService = llmAdminService;
    }

    @GetMapping("/completeness")
    public LlmAdminService.CompletenessView completeness() {
        return llmAdminService.completeness();
    }

    @GetMapping("/providers")
    public List<LlmAdminService.ProviderView> listProviders() {
        return llmAdminService.listProviders();
    }

    @PostMapping("/providers")
    @AuditAction(target = "provider_config", action = "create", summary = "新增模型供应商")
    public LlmAdminService.ProviderView createProvider(
            @RequestBody LlmAdminService.ProviderUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("code", request.code()));
        return llmAdminService.upsertProvider(null, request);
    }

    @PutMapping("/providers/{id}")
    @AuditAction(target = "provider_config", action = "update", summary = "更新模型供应商")
    public LlmAdminService.ProviderView updateProvider(
            @PathVariable long id,
            @RequestBody LlmAdminService.ProviderUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("id", id, "code", request.code()));
        return llmAdminService.upsertProvider(id, request);
    }

    @GetMapping("/models")
    public List<LlmAdminService.ModelView> listModels(@RequestParam(required = false) String providerCode) {
        return llmAdminService.listModels(providerCode);
    }

    @PostMapping("/models")
    @AuditAction(target = "model_config", action = "create", summary = "新增模型配置")
    public LlmAdminService.ModelView createModel(
            @RequestBody LlmAdminService.ModelUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("modelId", request.modelId()));
        return llmAdminService.upsertModel(null, request);
    }

    @PutMapping("/models/{id}")
    @AuditAction(target = "model_config", action = "update", summary = "更新模型配置")
    public LlmAdminService.ModelView updateModel(
            @PathVariable long id,
            @RequestBody LlmAdminService.ModelUpsertRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("id", id, "modelId", request.modelId()));
        return llmAdminService.upsertModel(id, request);
    }

    @GetMapping("/slots")
    public List<LlmAdminService.SlotView> listSlots() {
        return llmAdminService.listSlots();
    }

    @PutMapping("/slots/{slotName}")
    @AuditAction(target = "model_slot", action = "update", summary = "更新模型槽位绑定")
    public LlmAdminService.SlotView updateSlot(
            @PathVariable String slotName,
            @RequestBody LlmAdminService.SlotUpdateRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "slotName", slotName, "providerCode", request.providerCode(), "modelId", request.modelId()));
        return llmAdminService.updateSlot(slotName, request);
    }

    @PostMapping("/embedding/reembed")
    @AuditAction(target = "reembed_job", action = "start", summary = "发起全量记忆重嵌入迁移")
    public LlmAdminService.ReembedJobView startReembed(
            @RequestBody LlmAdminService.ReembedRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of("targetModelId", request.targetModelId()));
        return llmAdminService.startReembed(request.targetModelId(), request.confirm(), operator);
    }

    @GetMapping("/embedding/reembed/jobs")
    public PageResult<LlmAdminService.ReembedJobView> listReembedJobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return llmAdminService.listReembedJobs(page, pageSize);
    }

    @GetMapping("/embedding/reembed/jobs/{jobId}")
    public LlmAdminService.ReembedJobView getReembedJob(@PathVariable long jobId) {
        return llmAdminService.getReembedJob(jobId);
    }

    @GetMapping("/credentials")
    public List<LlmAdminService.CredentialView> listCredentials(
            @RequestParam(required = false) String ownerKind,
            @RequestParam(required = false) String ownerCode) {
        return llmAdminService.listCredentials(ownerKind, ownerCode);
    }

    @PostMapping("/credentials")
    @AuditAction(target = "credential", action = "create", summary = "新增凭证")
    public LlmAdminService.CredentialView addCredential(
            @RequestBody LlmAdminService.CredentialCreateRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "ownerKind", request.ownerKind(), "ownerCode", request.ownerCode()));
        return llmAdminService.addCredential(request);
    }

    @PutMapping("/credentials/{id}/enabled")
    @AuditAction(target = "credential", action = "set_enabled", summary = "启停凭证")
    public void setCredentialEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        llmAdminService.setCredentialEnabled(id, enabled);
    }

    @DeleteMapping("/credentials/{id}")
    @AuditAction(target = "credential", action = "delete", summary = "删除已停用凭证")
    public void deleteCredential(@PathVariable long id) {
        llmAdminService.deleteCredential(id);
    }
}
