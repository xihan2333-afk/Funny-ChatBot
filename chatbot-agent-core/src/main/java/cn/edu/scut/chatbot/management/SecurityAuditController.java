package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.security.audit.SecurityAuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全与操作审计检索 REST（006 US7 / T089）。
 */
@RestController
@RequestMapping("/api/management/security")
public class SecurityAuditController {

    private final SecurityAuditQueryService securityAuditQueryService;

    public SecurityAuditController(SecurityAuditQueryService securityAuditQueryService) {
        this.securityAuditQueryService = securityAuditQueryService;
    }

    @GetMapping("/audit-events")
    public PageResult<SecurityAuditQueryService.AuditEventView> listAuditEvents(
            @RequestParam(required = false) String eventKind,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return securityAuditQueryService.queryAuditEvents(
                eventKind, platform, botId, groupId, traceId, keyword, from, to, page, pageSize);
    }
}
