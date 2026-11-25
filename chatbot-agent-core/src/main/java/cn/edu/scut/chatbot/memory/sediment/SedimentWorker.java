package cn.edu.scut.chatbot.memory.sediment;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.context.compaction.SedimentSuggestionEvent;
import cn.edu.scut.chatbot.memory.MemoryErrorCodes;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 沉淀 worker（contracts/sediment-pipeline.md §3、research R2）：
 *
 * @Scheduled 轮询 pending 工单批量认领逐单处理——处理次序
 * CAS 认领 → 抽取 → 去重合并 → MySQL 落库（含画像增量）→ 异步批量向量化 →
 * Qdrant upsert → 置 done 回填 output_ref。
 *
 * <p>并发闸（宪法「新增并发入口必须同时提交限流方案」）：5 秒轮询周期 +
 * 单轮认领 claimBatch（默认 10）+ 处理侧 Semaphore 并发上界 10（虚拟线程逐单处理，
 * 不进 Effects 串行通道——几十秒级批调用塞 Effects 会阻塞该群 generation 派发）。
 *
 * <p>异常置 failed 递增 retry_count、超限告警终态不再自动重投（兜底扫描与
 * 管理面手动重投收口）；单群工单不要求串行（两级幂等兜底）。
 */
@Component
public class SedimentWorker {

    private static final Logger log = LoggerFactory.getLogger(SedimentWorker.class);

    /**
     * 处理并发上界（提交侧闸——与 claimBatch 缺省同量级）
     */
    private static final int PROCESS_CONCURRENCY = 10;

    /**
     * 既有相近记忆提示的条数上限（抽取输入有界）
     */
    private static final int EXISTING_MEMORY_HINT = 20;

    private final SedimentJobMapper jobMapper;
    private final SedimentTranscriptMapper transcriptMapper;
    private final ExtractionService extractionService;
    private final DedupMerger dedupMerger;
    private final EmbeddingWriter embeddingWriter;
    private final ProfileSignalApplier profileSignalApplier;
    private final MemoryLifecycleService lifecycleService;
    private final MemoryEntryMapper entryMapper;
    private final SystemParamService systemParams;
    private final Clock clock;

    private final Semaphore permits = new Semaphore(PROCESS_CONCURRENCY);

    public SedimentWorker(SedimentJobMapper jobMapper,
                          SedimentTranscriptMapper transcriptMapper,
                          ExtractionService extractionService,
                          DedupMerger dedupMerger,
                          EmbeddingWriter embeddingWriter,
                          ProfileSignalApplier profileSignalApplier,
                          MemoryLifecycleService lifecycleService,
                          MemoryEntryMapper entryMapper,
                          SystemParamService systemParams,
                          Clock clock) {
        this.jobMapper = jobMapper;
        this.transcriptMapper = transcriptMapper;
        this.extractionService = extractionService;
        this.dedupMerger = dedupMerger;
        this.embeddingWriter = embeddingWriter;
        this.profileSignalApplier = profileSignalApplier;
        this.lifecycleService = lifecycleService;
        this.entryMapper = entryMapper;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    /**
     * 轮询周期 5 秒（认领 → 派发虚拟线程处理；无工单一轮空转开销一次索引查询）
     */
    @Scheduled(fixedDelay = 5000)
    public void poll() {
        WorkerParams params = workerParams();
        List<SedimentJobDO> claimable = jobMapper.selectClaimable(params.retryMax, params.claimBatch);
        for (SedimentJobDO job : claimable) {
            if (!permits.tryAcquire()) {
                break;   // 并发闸满：工单保持 pending 留给下一轮
            }
            int claimed = jobMapper.claimJob(job.getId(), params.retryMax);
            if (claimed == 0) {
                permits.release();
                continue;   // CAS 竞争只一方认领
            }
            Thread.ofVirtual().name("sediment-worker-" + job.getId()).start(() -> {
                try {
                    process(job);
                } finally {
                    permits.release();
                }
            });
        }
    }

    /**
     * 压缩完成即时唤醒（提前拉单——正确性不依赖本事件，丢失由每日兜底兜住）
     */
    @EventListener
    public void onSedimentSuggestion(SedimentSuggestionEvent event) {
        Thread.ofVirtual().name("sediment-wake").start(this::poll);
    }

    /**
     * 单工单处理（认领后调用；公开供测试直驱）：抽取 → 去重合并 → 画像增量 →
     * 异步向量化 → done。异常一律吞掉置 failed——单工单失败不影响同批其他工单
     */
    public void process(SedimentJobDO job) {
        try {
            GroupKey group = new GroupKey(job.getPlatform(), job.getBotId(), job.getGroupId());
            JobPayload payload = parsePayload(job.getPayload());
            List<SedimentTranscriptDO> entries = loadRange(job, payload);
            String latestSummary = loadSummaryText(job, payload);
            List<String> existingContents = existingMemoryHint(job);

            ExtractionContract.ExtractionOutput output = extractionService.extract(
                    group, entries, latestSummary, existingContents);

            DedupMerger.MergeOutcome outcome = dedupMerger.mergeCandidates(
                    group, job.getId(), output.candidateEntries());

            List<Long> supersededOldIds = applyConflicts(group, job, output.conflicts(),
                    outcome.createdIds());

            String profileChanges = profileSignalApplier.apply(group, output.profileSignals());

            // 异步批量向量化（MySQL 先落、Qdrant 后写；新建与正文重写的都要嵌）
            List<Long> toEmbed = new ArrayList<>(outcome.createdIds());
            toEmbed.addAll(outcome.mergedIds());
            embeddingWriter.enqueueAsync(group, toEmbed);

            jobMapper.markDone(job.getId(), outputRefJson(outcome, supersededOldIds,
                    profileChanges, toEmbed));
            log.info("沉淀工单完成 jobId={} 新建 {} 合并 {} 取代 {} 画像 [{}]",
                    job.getId(), outcome.createdIds().size(), outcome.mergedIds().size(),
                    supersededOldIds.size(),
                    profileChanges == null ? "无变更" : profileChanges);
        } catch (Exception e) {
            jobMapper.markFailed(job.getId());
            SedimentJobDO reloaded = jobMapper.selectById(job.getId());
            if (reloaded != null && reloaded.getRetryCount() != null
                    && reloaded.getRetryCount() >= workerParams().retryMax) {
                log.error("沉淀工单重试超限进终态（告警口径——管理面可手动重投）"
                                + " errorCode={} jobId={} retryCount={} traceId=n/a",
                        MemoryErrorCodes.SEDIMENT_EXTRACTION_INVALID, job.getId(),
                        reloaded.getRetryCount(), e);
            } else {
                log.warn("沉淀工单处理失败（置 failed 待重投） jobId={}：{}",
                        job.getId(), e.getMessage());
            }
        }
    }

    /**
     * 冲突取代：existingContent 匹配既有 active 条目、newContent 匹配本批新建条目 →
     * 旧条 superseded + superseded_by 互链
     */
    private List<Long> applyConflicts(GroupKey group, SedimentJobDO job,
                                      List<ExtractionContract.Conflict> conflicts,
                                      List<Long> createdIds) {
        List<Long> superseded = new ArrayList<>();
        if (conflicts.isEmpty()) {
            return superseded;
        }
        List<MemoryEntryDO> existing = entryMapper.selectActiveByGroup(
                group.platform(), group.botId(), group.groupId());
        List<MemoryEntryDO> created = createdIds.isEmpty()
                ? List.of() : entryMapper.selectByIds(createdIds);
        for (ExtractionContract.Conflict conflict : conflicts) {
            MemoryEntryDO old = findByContent(existing, conflict.existingContent());
            MemoryEntryDO fresh = findByContent(created, conflict.newContent());
            if (old != null && fresh != null && !old.getId().equals(fresh.getId())) {
                lifecycleService.supersede(old.getId(), fresh.getId());
                superseded.add(old.getId());
            } else {
                log.info("冲突条目未能定位两端（跳过取代留待下批） old={} new={}",
                        conflict.existingContent(), conflict.newContent());
            }
        }
        return superseded;
    }

    private static MemoryEntryDO findByContent(List<MemoryEntryDO> entries, String content) {
        for (MemoryEntryDO entry : entries) {
            if (content.equals(entry.getContent())) {
                return entry;
            }
        }
        return null;
    }

    private List<SedimentTranscriptDO> loadRange(SedimentJobDO job, JobPayload payload) {
        return transcriptMapper.selectByGroupAndIdRange(job.getPlatform(), job.getBotId(),
                job.getGroupId(), payload.firstEntryId, payload.lastEntryId);
    }

    /**
     * 压缩工单的「当时最新摘要」（payload 的 summaryEntryId 指向本事务刚落的摘要条目）
     */
    private String loadSummaryText(SedimentJobDO job, JobPayload payload) {
        if (payload.summaryEntryId <= 0) {
            return null;
        }
        List<SedimentTranscriptDO> rows = transcriptMapper.selectByGroupAndIdRange(
                job.getPlatform(), job.getBotId(), job.getGroupId(),
                payload.summaryEntryId, payload.summaryEntryId);
        if (rows.isEmpty() || !SedimentTranscriptDO.KIND_SUMMARY.equals(rows.get(0).getKind())) {
            return null;
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(rows.get(0).getPayload());
            return node.path("summaryText").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 该群既有相近记忆提示（去重与判矛盾的参照，有界）
     */
    private List<String> existingMemoryHint(SedimentJobDO job) {
        List<MemoryEntryDO> active = entryMapper.selectActiveByGroup(
                job.getPlatform(), job.getBotId(), job.getGroupId());
        if (active.size() > EXISTING_MEMORY_HINT) {
            active = active.subList(active.size() - EXISTING_MEMORY_HINT, active.size());
        }
        return active.stream().map(MemoryEntryDO::getContent).toList();
    }

    private JobPayload parsePayload(String payloadJson) {
        long summaryEntryId = 0;
        long firstEntryId = 0;
        long lastEntryId = 0;
        try {
            JsonNode node = JsonMappers.shared().readTree(payloadJson);
            summaryEntryId = node.path("summaryEntryId").asLong(0);
            firstEntryId = node.path("firstEntryId").asLong(0);
            lastEntryId = node.path("lastEntryId").asLong(0);
        } catch (Exception e) {
            log.warn("工单 payload 解析失败按空区间处理 jobId={}", e.getMessage());
        }
        return new JobPayload(summaryEntryId, firstEntryId, lastEntryId);
    }

    private String outputRefJson(DedupMerger.MergeOutcome outcome, List<Long> supersededOldIds,
                                 String profileChanges, List<Long> embeddedIds) {
        ObjectNode output = JsonMappers.shared().createObjectNode();
        output.set("created", JsonMappers.shared().valueToTree(outcome.createdIds()));
        output.set("merged", JsonMappers.shared().valueToTree(outcome.mergedIds()));
        output.set("superseded", JsonMappers.shared().valueToTree(supersededOldIds));
        output.set("embedded", JsonMappers.shared().valueToTree(embeddedIds));
        if (profileChanges != null) {
            output.put("profileChanges", profileChanges);
        }
        return output.toString();
    }

    private record JobPayload(long summaryEntryId, long firstEntryId, long lastEntryId) {
    }

    /**
     * worker 参数（system_param memory.sediment.worker 热变更）
     */
    public WorkerParams workerParams() {
        int claimBatch = 10;
        int retryMax = 3;
        long processingTimeoutMinutes = 30;
        long keepDoneDays = 30;
        String raw = systemParams.getString("memory.sediment.worker", null);
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode node = JsonMappers.shared().readTree(raw);
                claimBatch = node.path("claimBatch").asInt(claimBatch);
                retryMax = node.path("retryMax").asInt(retryMax);
                processingTimeoutMinutes = node.path("processingTimeoutMinutes").asLong(processingTimeoutMinutes);
                keepDoneDays = node.path("keepDoneDays").asLong(keepDoneDays);
            } catch (Exception e) {
                log.warn("memory.sediment.worker 解析失败走缺省：{}", e.getMessage());
            }
        }
        return new WorkerParams(claimBatch, retryMax, processingTimeoutMinutes, keepDoneDays);
    }

    public record WorkerParams(int claimBatch, int retryMax, long processingTimeoutMinutes,
                               long keepDoneDays) {
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
