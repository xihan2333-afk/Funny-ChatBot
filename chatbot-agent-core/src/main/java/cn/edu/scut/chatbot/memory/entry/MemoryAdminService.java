package cn.edu.scut.chatbot.memory.entry;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.management.PageResult;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobDO;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆管理门面服务（A5）：多维检索、完整溯源链（memory ← sedimentJob ← transcript）、
 * 取代时间线、编辑重嵌入、双库同删、归档恢复、撤销取代。
 */
@Service
public class MemoryAdminService {

    private final MemoryEntryMapper memoryEntryMapper;
    private final MemoryLifecycleService lifecycleService;
    private final SedimentJobMapper sedimentJobMapper;
    private final AgentSessionMapper sessionMapper;
    private final Clock clock;

    public MemoryAdminService(MemoryEntryMapper memoryEntryMapper,
                              MemoryLifecycleService lifecycleService,
                              SedimentJobMapper sedimentJobMapper,
                              AgentSessionMapper sessionMapper,
                              Clock clock) {
        this.memoryEntryMapper = memoryEntryMapper;
        this.lifecycleService = lifecycleService;
        this.sedimentJobMapper = sedimentJobMapper;
        this.sessionMapper = sessionMapper;
        this.clock = clock;
    }

    public PageResult<MemorySummaryView> listMemories(
            String platform, String botId, String groupId, String memoryType,
            String userId, String status, String keyword, String fromIso, String toIso,
            int page, int pageSize) {
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        LocalDateTime from = parseIso(fromIso);
        LocalDateTime to = parseIso(toIso);
        long total = memoryEntryMapper.countAdminPaged(platform, botId, groupId, memoryType, userId, status, keyword, from, to);
        List<MemorySummaryView> items = memoryEntryMapper.selectAdminPaged(
                        platform, botId, groupId, memoryType, userId, status, keyword, from, to,
                        PageResult.offset(p, ps), ps).stream()
                .map(m -> new MemorySummaryView(
                        m.getId(), m.getPlatform(), m.getBotId(), m.getGroupId(),
                        m.getMemoryType(), m.getContent(), m.getUserId(),
                        m.getStrength() != null ? m.getStrength() : 0,
                        m.getStatus(), m.getSupersededBy(),
                        m.getOccurredAt() != null ? m.getOccurredAt().toString() : null))
                .toList();
        return PageResult.of(items, total, p, ps);
    }

    public MemoryDetailView getDetail(long memoryId) {
        MemoryEntryDO m = requireMemory(memoryId);
        SedimentJobView jobView = null;
        List<TranscriptSnippetView> transcripts = List.of();

        Long jobId = extractJobId(m.getSourceRef());
        if (jobId != null) {
            SedimentJobDO job = sedimentJobMapper.selectById(jobId);
            if (job != null) {
                Long sessionId = null;
                long startId = 0L;
                long endId = Long.MAX_VALUE;
                if (job.getPayload() != null && !job.getPayload().isBlank()) {
                    try {
                        JsonNode node = JsonMappers.shared().readTree(job.getPayload());
                        if (node.hasNonNull("sessionId")) sessionId = node.get("sessionId").asLong();
                        if (node.hasNonNull("startEntryId")) startId = node.get("startEntryId").asLong();
                        if (node.hasNonNull("endEntryId")) endId = node.get("endEntryId").asLong();
                    } catch (Exception ignored) {
                    }
                }
                if (sessionId == null && job.getPlatform() != null && job.getBotId() != null && job.getGroupId() != null) {
                    var sess = sessionMapper.selectByGroup(job.getPlatform(), job.getBotId(), job.getGroupId());
                    if (sess != null) sessionId = sess.getId();
                }
                jobView = new SedimentJobView(
                        job.getId(), sessionId, startId == 0L ? null : startId, endId == Long.MAX_VALUE ? null : endId,
                        job.getStatus(), job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);
                if (sessionId != null) {
                    final long minId = startId;
                    final long maxId = endId;
                    transcripts = sessionMapper.selectOrdered(sessionId).stream()
                            .filter(t -> t.getId() >= minId && t.getId() <= maxId)
                            .map(t -> new TranscriptSnippetView(
                                    t.getId(), t.getKind(), t.getPayload(),
                                    t.getOccurredAt() != null ? t.getOccurredAt().toString() : null))
                            .toList();
                }
            }
        }

        MemorySummaryView supersededByEntry = null;
        if (m.getSupersededBy() != null) {
            MemoryEntryDO next = memoryEntryMapper.selectById(m.getSupersededBy());
            if (next != null) {
                supersededByEntry = new MemorySummaryView(
                        next.getId(), next.getPlatform(), next.getBotId(), next.getGroupId(),
                        next.getMemoryType(), next.getContent(), next.getUserId(),
                        next.getStrength() != null ? next.getStrength() : 0,
                        next.getStatus(), next.getSupersededBy(),
                        next.getOccurredAt() != null ? next.getOccurredAt().toString() : null);
            }
        }

        return new MemoryDetailView(
                m.getId(), m.getPlatform(), m.getBotId(), m.getGroupId(),
                m.getMemoryType(), m.getContent(), m.getUserId(), m.getKeywords(),
                m.getStrength() != null ? m.getStrength() : 0,
                m.getStatus(), m.getSupersededBy(), m.getSourceRef(),
                m.getOccurredAt() != null ? m.getOccurredAt().toString() : null,
                jobView, transcripts, supersededByEntry);
    }

    public void reviseContent(long memoryId, String newContent) {
        MemoryEntryDO m = requireMemory(memoryId);
        lifecycleService.reviseContent(new GroupKey(m.getPlatform(), m.getBotId(), m.getGroupId()), memoryId, newContent);
    }

    public void deleteMemory(long memoryId) {
        MemoryEntryDO m = requireMemory(memoryId);
        lifecycleService.deleteMemory(new GroupKey(m.getPlatform(), m.getBotId(), m.getGroupId()), memoryId);
    }

    public void restoreArchived(long memoryId) {
        MemoryEntryDO m = requireMemory(memoryId);
        int strength = m.getStrength() != null ? Math.max(m.getStrength(), 50) : 50;
        memoryEntryMapper.updateStrengthAndStatus(
                memoryId, strength, MemoryEntryDO.STATUS_ACTIVE, LocalDateTime.now(clock));
    }

    public void revokeSupersede(long memoryId) {
        requireMemory(memoryId);
        lifecycleService.revokeSupersede(memoryId);
    }

    private MemoryEntryDO requireMemory(long memoryId) {
        MemoryEntryDO m = memoryEntryMapper.selectById(memoryId);
        if (m == null) {
            throw new ChatBotException("mgmt.not_found", "记忆条目不存在 id=" + memoryId);
        }
        return m;
    }

    private static Long extractJobId(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) return null;
        try {
            JsonNode node = JsonMappers.shared().readTree(sourceRef);
            if (node.hasNonNull("sedimentJobId")) return node.get("sedimentJobId").asLong();
            if (node.hasNonNull("jobId")) return node.get("jobId").asLong();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static LocalDateTime parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    public record MemorySummaryView(
            Long id, String platform, String botId, String groupId,
            String memoryType, String content, String userId,
            int strength, String status, Long supersededBy, String occurredAt
    ) {
    }

    public record SedimentJobView(
            Long id, Long sessionId, Long startEntryId, Long endEntryId, String status, String createdAt
    ) {
    }

    public record TranscriptSnippetView(Long id, String kind, String payload, String occurredAt) {
    }

    public record MemoryDetailView(
            Long id, String platform, String botId, String groupId,
            String memoryType, String content, String userId, String keywords,
            int strength, String status, Long supersededBy, String sourceRef, String occurredAt,
            SedimentJobView sedimentJob,
            List<TranscriptSnippetView> transcriptEntries,
            MemorySummaryView supersededByEntry
    ) {
    }
}
