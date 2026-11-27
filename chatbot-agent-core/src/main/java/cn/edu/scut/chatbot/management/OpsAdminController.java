package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.llm.slot.LlmAdminService;
import cn.edu.scut.chatbot.security.credential.CredentialRotationOpsService;
import org.springframework.web.bind.annotation.*;

/**
 * 运维工具 REST（A12）：主密钥轮换触发与历史查询、重嵌入迁移历史查询。
 */
@RestController
@RequestMapping("/api/management/ops")
public class OpsAdminController {

    private final CredentialRotationOpsService rotationOpsService;
    private final LlmAdminService llmAdminService;

    public OpsAdminController(CredentialRotationOpsService rotationOpsService,
                              LlmAdminService llmAdminService) {
        this.rotationOpsService = rotationOpsService;
        this.llmAdminService = llmAdminService;
    }

    @PostMapping("/credential-rotation")
    @SkipAutoAudit
    public CredentialRotationOpsService.RotationLogView rotateCredential(
            @RequestBody RotationRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        if (request == null || !request.confirm()) {
            throw new IllegalArgumentException("主密钥轮换是高危不可逆操作，必须传 confirm=true 确认");
        }
        return rotationOpsService.triggerRotation(
                request.newMasterKeyBase64(), request.useEnvStagedKey(), operator);
    }

    @GetMapping("/credential-rotation/history")
    public PageResult<CredentialRotationOpsService.RotationLogView> rotationHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return rotationOpsService.listHistory(page, pageSize);
    }

    @GetMapping("/reembed-history")
    public PageResult<LlmAdminService.ReembedJobView> reembedHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return llmAdminService.listReembedJobs(page, pageSize);
    }

    public record RotationRequest(String newMasterKeyBase64, boolean useEnvStagedKey, boolean confirm) {
    }
}
