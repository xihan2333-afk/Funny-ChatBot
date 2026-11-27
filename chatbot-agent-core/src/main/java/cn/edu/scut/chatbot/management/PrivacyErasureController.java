package cn.edu.scut.chatbot.management;

import org.springframework.web.bind.annotation.*;

/**
 * 个体隐私数据清除管理 REST（US12 T104，admin-auth 契约第 4 节）：
 * 仅对已认证管理员开放，要求显式范围确认 {@code confirm=true}（不建群友自助通道——明文决策）。
 */
@RestController
@RequestMapping("/api/management/privacy")
public class PrivacyErasureController {

    private final PrivacyErasureService privacyErasureService;

    public PrivacyErasureController(PrivacyErasureService privacyErasureService) {
        this.privacyErasureService = privacyErasureService;
    }

    @GetMapping("/preview")
    public PrivacyErasureService.ErasurePreview preview(
            @RequestParam String platform,
            @RequestParam String botId,
            @RequestParam String groupId,
            @RequestParam String userId) {
        return privacyErasureService.preview(platform, botId, groupId, userId);
    }

    @PostMapping("/erase")
    @SkipAutoAudit
    public PrivacyErasureService.ErasureReport erase(
            @RequestBody ErasureRequest request,
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        if (request == null || !request.confirm()) {
            throw new IllegalArgumentException("个体数据清除是不可逆操作，必须显式传 confirm=true 确认清除范围");
        }
        return privacyErasureService.erase(
                request.platform(), request.botId(), request.groupId(), request.userId(), operator);
    }

    public record ErasureRequest(
            String platform,
            String botId,
            String groupId,
            String userId,
            boolean confirm
    ) {
    }
}
