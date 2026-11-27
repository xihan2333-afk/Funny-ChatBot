package cn.edu.scut.chatbot.security.audit;

import cn.edu.scut.chatbot.management.PageResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 安全与操作审计查询门面（006 US7 / T089）：收口 SecurityEventMapper，不向 management 暴露 DO/Mapper。
 */
@Service
public class SecurityAuditQueryService {

    private final SecurityEventMapper securityEventMapper;

    public SecurityAuditQueryService(SecurityEventMapper securityEventMapper) {
        this.securityEventMapper = securityEventMapper;
    }

    public PageResult<AuditEventView> queryAuditEvents(
            String eventKind, String platform, String botId, String groupId,
            String traceId, String keyword, String fromIso, String toIso,
            int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        LocalDateTime from = parseIso(fromIso);
        LocalDateTime to = parseIso(toIso);
        long total = securityEventMapper.countPage(
                eventKind, platform, botId, groupId, traceId, keyword, from, to);
        List<AuditEventView> items = securityEventMapper.selectPage(
                        eventKind, platform, botId, groupId, traceId, keyword, from, to,
                        PageResult.offset(p, ps), ps).stream()
                .map(e -> new AuditEventView(
                        e.getId(), e.getEventKind(), e.getPlatform(), e.getBotId(), e.getGroupId(),
                        e.getUserId(), e.getTraceId(), e.getDetail(),
                        e.getCreatedAt() != null ? e.getCreatedAt().toString() : null))
                .toList();
        return PageResult.of(items, total, p, ps);
    }

    private static LocalDateTime parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    public record AuditEventView(
            Long id, String eventKind, String platform, String botId, String groupId,
            String userId, String traceId, String detail, String occurredAt
    ) {
    }
}
