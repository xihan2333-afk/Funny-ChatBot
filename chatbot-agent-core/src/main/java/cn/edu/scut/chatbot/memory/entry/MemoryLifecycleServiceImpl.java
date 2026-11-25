package cn.edu.scut.chatbot.memory.entry;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.memory.profile.UserProfileMapper;
import cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆生命周期实现（contracts/sediment-pipeline.md §5、research R11）：
 * 衰减读写共用 {@link MemoryDecay} 单一纯函数（防口径漂移）；
 * 批任务分群分批限量（避免长事务）；软上限加速最低强度衰减（先忘不重要的、无硬删）。
 */
@Component
public class MemoryLifecycleServiceImpl implements MemoryLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleServiceImpl.class);

    /**
     * 每日批任务单群处理条数上限（分批限量——避免长事务；幂等可重入，下轮接着处理）
     */
    static final int PER_GROUP_BATCH = 500;

    /**
     * 单轮归档清理行数上限（物理清理批量限次）
     */
    static final int ARCHIVE_CLEAN_BATCH = 500;

    /**
     * 软上限触发时的额外衰减倍率（最低强度条目加速遗忘）
     */
    static final int SOFT_LIMIT_EXTRA_RATE = 3;

    private final MemoryEntryMapper entryMapper;
    private final MemoryRetriever retriever;
    private final UserProfileMapper profileMapper;
    private final SystemParamService systemParams;
    private final Clock clock;

    public MemoryLifecycleServiceImpl(MemoryEntryMapper entryMapper,
                                      MemoryRetriever retriever,
                                      UserProfileMapper profileMapper,
                                      SystemParamService systemParams,
                                      Clock clock) {
        this.entryMapper = entryMapper;
        this.retriever = retriever;
        this.profileMapper = profileMapper;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    @Override
    public void reinforce(List<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        entryMapper.reinforceBatch(memoryIds, boostPerHit(), LocalDateTime.now(clock));
    }

    @Override
    public void runDailyDecayAndArchive() {
        MemoryDecay.DecayParams params = decayParams();
        LocalDateTime now = LocalDateTime.now(clock);
        List<MemoryGroupDO> groups = entryMapper.selectActiveGroups();
        int archived = 0;
        int decayed = 0;
        for (MemoryGroupDO group : groups) {
            long activeCount = entryMapper.countActiveByGroup(
                    group.getPlatform(), group.getBotId(), group.getGroupId());
            boolean overSoftLimit = activeCount > params.groupSoftLimit();
            List<MemoryEntryDO> entries = entryMapper.selectActiveByGroup(
                    group.getPlatform(), group.getBotId(), group.getGroupId());
            int handled = 0;
            for (MemoryEntryDO entry : entries) {
                if (handled >= PER_GROUP_BATCH) {
                    break;   // 分批限量：剩余下轮接着（批任务幂等）
                }
                double effective = MemoryDecay.effectiveStrength(
                        entry.getStrength(),
                        occurredInstant(entry), lastAccessedInstant(entry),
                        clock.instant(), params);
                double finalStrength = effective;
                if (overSoftLimit) {
                    // 软上限加速：先忘不重要的——最低强度条目额外衰减（无硬删）
                    finalStrength = Math.max(0, effective
                            - params.dailyDecayRate() * SOFT_LIMIT_EXTRA_RATE);
                }
                if (finalStrength < params.archiveThreshold()) {
                    entryMapper.updateStrengthAndStatus(entry.getId(),
                            (int) Math.round(finalStrength), MemoryEntryDO.STATUS_ARCHIVED, now);
                    archived++;
                } else if (Math.round(finalStrength) != entry.getStrength()) {
                    entryMapper.updateStrengthAndStatus(entry.getId(),
                            (int) Math.round(finalStrength), MemoryEntryDO.STATUS_ACTIVE, now);
                    decayed++;
                }
                handled++;
            }
            if (overSoftLimit) {
                log.warn("群超软上限加速遗忘（无硬删——负向断言锚点） group={}:{}/{} active 数 {} > {}",
                        group.getGroupId(), group.getBotId(), activeCount, params.groupSoftLimit());
            }
        }
        // 归档满保留期物理清理（updated_at 即归档时刻）
        int purged = entryMapper.deleteArchivedBefore(
                now.minusDays(params.archiveKeepDays()), ARCHIVE_CLEAN_BATCH);
        log.info("每日衰减批完成 groups={} 衰减 {} 条 归档 {} 条 物理清理 {} 条",
                groups.size(), decayed, archived, purged);
    }

    @Override
    public void supersede(long oldId, long newId) {
        entryMapper.supersede(oldId, newId);
        log.info("记忆冲突取代 oldId={} newId={}（旧条留痕 superseded_by 互链）", oldId, newId);
    }

    @Override
    public void revokeSupersede(long oldId) {
        entryMapper.revokeSupersede(oldId);
        log.info("撤销取代回 active memoryId={}", oldId);
    }

    @Override
    public void reviseContent(GroupKey group, long memoryId, String newContent) {
        MemoryEntryDO entry = entryMapper.selectById(memoryId);
        if (entry == null || !group.platform().equals(entry.getPlatform())
                || !group.botId().equals(entry.getBotId())
                || !group.groupId().equals(entry.getGroupId())) {
            throw new cn.edu.scut.chatbot.exception.CommandRejectedException(
                    "记忆条目不存在或不属于该群 memoryId=" + memoryId);
        }
        entryMapper.updateContent(memoryId, newContent);
        retriever.reembed(group, memoryId);   // 修正派生动作：正文变更 → 同 id 重嵌入
    }

    @Override
    public void deleteMemory(GroupKey group, long memoryId) {
        MemoryEntryDO entry = entryMapper.selectById(memoryId);
        if (entry == null) {
            return;
        }
        entryMapper.deleteByIds(List.of(memoryId));
        retriever.deleteFromIndex(List.of(memoryId));   // 两库同删
    }

    @Override
    public void purgeMemberData(GroupKey group, String userId) {
        List<MemoryEntryDO> memberEntries = entryMapper.selectActiveByGroup(
                        group.platform(), group.botId(), group.groupId()).stream()
                .filter(entry -> userId.equals(entry.getUserId()))
                .toList();
        if (!memberEntries.isEmpty()) {
            List<Long> ids = memberEntries.stream().map(MemoryEntryDO::getId).toList();
            entryMapper.deleteByIds(ids);
            retriever.deleteFromIndex(ids);   // 同步 Qdrant 删除
        }
        profileMapper.deleteByGroupAndUser(group.platform(), group.botId(),
                group.groupId(), userId);
        log.info("个体认知数据清除 group={} userId={} 记忆 {} 条 + 画像（转录清除归管理面编排）",
                group.asMapKey(), userId, memberEntries.size());
    }

    // ===== 参数装载 =====

    private int boostPerHit() {
        return cn.edu.scut.chatbot.memory.retrieve.RetrieveParams.load(systemParams).boostPerHit();
    }

    /**
     * 衰减参数装载（读侧检索重排与写侧批任务共用 MemoryDecay.load——单一实现防口径漂移）
     */
    public MemoryDecay.DecayParams decayParams() {
        return MemoryDecay.load(systemParams);
    }

    private java.time.Instant occurredInstant(MemoryEntryDO entry) {
        return entry.getOccurredAt() == null ? clock.instant()
                : entry.getOccurredAt().atZone(clock.getZone()).toInstant();
    }

    private java.time.Instant lastAccessedInstant(MemoryEntryDO entry) {
        return entry.getLastAccessedAt() == null ? null
                : entry.getLastAccessedAt().atZone(clock.getZone()).toInstant();
    }
}
