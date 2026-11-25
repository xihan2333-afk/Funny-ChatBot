package cn.edu.scut.chatbot.memory.sediment;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 每日兜底扫描（contracts/sediment-pipeline.md §2）：每日低峰三件事——
 * <ol>
 *   <li>重投超时 processing 工单（processingTimeout 由 updated_at 心跳判定，30 分钟）；</li>
 *   <li>扫描无工单覆盖的转录尾部按区间补投（安静群长期不压缩；幂等键 = 扫描批次标识，
 *       与压缩落单并发时唯一索引拒插后到者丢弃）；</li>
 *   <li>done 行超 keepDoneDays（30 天）清理。</li>
 * </ol>
 * 全部幂等可重入；failed 未超限由 worker 的认领条件自动重试、
 * failed 超限终态只告警（管理面手动重投）。
 */
@Component
public class SedimentFallbackScanner {

    private static final Logger log = LoggerFactory.getLogger(SedimentFallbackScanner.class);

    /**
     * 尾部补投单群条数上限（一次兜底处理的积压上界——剩余次日再补）
     */
    static final int TAIL_BATCH = 200;

    private static final DateTimeFormatter BATCH_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SedimentJobMapper jobMapper;
    private final SedimentTranscriptMapper transcriptMapper;
    private final SystemParamService systemParams;
    private final Clock clock;

    public SedimentFallbackScanner(SedimentJobMapper jobMapper,
                                   SedimentTranscriptMapper transcriptMapper,
                                   SystemParamService systemParams,
                                   Clock clock) {
        this.jobMapper = jobMapper;
        this.transcriptMapper = transcriptMapper;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    /**
     * 每日低峰触发（03:40——与 L1/演进等周期任务错峰）
     */
    @Scheduled(cron = "0 40 3 * * ?")
    public void runDailyFallback() {
        LocalDateTime now = LocalDateTime.now(clock);
        int reset = resetStaleProcessing(now);
        int supplemented = supplementUncoveredTail(now);
        int purged = cleanupDoneRows(now);
        log.info("沉淀兜底扫描完成 超时重投 {} 条 转录尾部补投 {} 群 done 清理 {} 行",
                reset, supplemented, purged);
    }

    /**
     * 1. 超时 processing 重投（活着的不动——CAS 条件含 updated_at 超时判定）
     */
    public int resetStaleProcessing(LocalDateTime now) {
        SedimentWorker.WorkerParams params = workerParams();
        LocalDateTime timeoutBefore = now.minusMinutes(params.processingTimeoutMinutes());
        List<SedimentJobDO> stale = jobMapper.selectStaleProcessing(timeoutBefore, 100);
        int reset = 0;
        for (SedimentJobDO job : stale) {
            reset += jobMapper.resetStaleProcessing(job.getId(), timeoutBefore);
        }
        if (!stale.isEmpty()) {
            log.warn("发现超时 processing 工单 {} 条（崩溃续跑口径——重投不产生重复条目，"
                    + "两级幂等兜底）", stale.size());
        }
        return reset;
    }

    /**
     * 2. 无工单覆盖的转录尾部补投（幂等键 = scan-{yyyyMMdd}——同日同群一批）
     */
    public int supplementUncoveredTail(LocalDateTime now) {
        List<SedimentGroupDO> groups = transcriptMapper.selectDistinctGroups();
        String batchKey = "scan-" + BATCH_DAY.format(LocalDate.now(clock));
        int supplemented = 0;
        for (SedimentGroupDO group : groups) {
            long coveredMax = coveredMaxEntryId(group);
            Long maxId = transcriptMapper.selectMaxEntryId(
                    group.getPlatform(), group.getBotId(), group.getGroupId());
            if (maxId == null || maxId <= coveredMax) {
                continue;
            }
            List<SedimentTranscriptDO> tail = transcriptMapper.selectAfterId(
                    group.getPlatform(), group.getBotId(), group.getGroupId(),
                    coveredMax, TAIL_BATCH);
            if (tail.isEmpty()) {
                continue;
            }
            SedimentJobDO job = new SedimentJobDO();
            job.setPlatform(group.getPlatform());
            job.setBotId(group.getBotId());
            job.setGroupId(group.getGroupId());
            job.setTriggerKind(SedimentJobDO.KIND_DAILY_SCAN);
            job.setIdempotentKey(batchKey);
            job.setPayload(payloadJson(tail.get(0).getId(),
                    tail.get(tail.size() - 1).getId()));
            job.setStatus(SedimentJobDO.STATUS_PENDING);
            job.setRetryCount(0);
            try {
                jobMapper.insertJob(job);
                supplemented++;
            } catch (DuplicateKeyException duplicate) {
                // 同批次已补投（幂等）；与压缩落单并发时后到者丢弃
            }
        }
        return supplemented;
    }

    /**
     * 3. done 行超期清理
     */
    public int cleanupDoneRows(LocalDateTime now) {
        return jobMapper.deleteDoneBefore(now.minusDays(workerParams().keepDoneDays()), 500);
    }

    /**
     * 该群工单已覆盖的最大转录条目 id（payload.lastEntryId 的最大值）
     */
    private long coveredMaxEntryId(SedimentGroupDO group) {
        long covered = 0;
        for (SedimentJobDO job : jobMapper.selectByGroup(
                group.getPlatform(), group.getBotId(), group.getGroupId())) {
            try {
                JsonNode payload = JsonMappers.shared().readTree(job.getPayload());
                long last = payload.path("lastEntryId").asLong(0);
                covered = Math.max(covered, last);
            } catch (Exception e) {
                // 坏 payload 按未覆盖处理（宁可重扫不漏扫——两级幂等兜底重复无害）
            }
        }
        return covered;
    }

    private String payloadJson(long firstEntryId, long lastEntryId) {
        ObjectNode payload = JsonMappers.shared().createObjectNode();
        payload.put("firstEntryId", firstEntryId);
        payload.put("lastEntryId", lastEntryId);
        return payload.toString();
    }

    private SedimentWorker.WorkerParams workerParams() {
        SedimentWorker.WorkerParams fallback = new SedimentWorker.WorkerParams(10, 3, 30, 30);
        String raw = systemParams.getString("memory.sediment.worker", null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(raw);
            return new SedimentWorker.WorkerParams(
                    node.path("claimBatch").asInt(fallback.claimBatch()),
                    node.path("retryMax").asInt(fallback.retryMax()),
                    node.path("processingTimeoutMinutes").asLong(fallback.processingTimeoutMinutes()),
                    node.path("keepDoneDays").asLong(fallback.keepDoneDays()));
        } catch (Exception e) {
            return fallback;
        }
    }
}
