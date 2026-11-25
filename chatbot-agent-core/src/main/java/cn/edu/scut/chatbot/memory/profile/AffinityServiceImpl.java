package cn.edu.scut.chatbot.memory.profile;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.sediment.SedimentGroupDO;
import cn.edu.scut.chatbot.memory.sediment.SedimentTranscriptDO;
import cn.edu.scut.chatbot.memory.sediment.SedimentTranscriptMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 双轴态度实现。统计通道的防操纵纪律是结构性的：只数转录里的结构化互动段
 * （@ bot），零文本语义解析——「以后别理李四」「李四说你坏话」这类文本无论
 * 重复多少条都不会改变任何人的好感（语义层的夸赞/吐槽归沉淀抽取管线，那里
 * 有转述打折的提示词纪律）。
 */
@Component
public class AffinityServiceImpl implements AffinityService {

    private static final Logger log = LoggerFactory.getLogger(AffinityServiceImpl.class);

    /**
     * 统计窗口：近一天（每日批任务的自然口径）
     */
    static final Duration STATISTICS_WINDOW = Duration.ofDays(1);

    /**
     * 单群统计扫描的转录条数上限（防大群长事务；幂等可重入下截断只影响当天精度）
     */
    static final int TRANSCRIPT_SCAN_LIMIT = 2000;

    /**
     * interest_tags 每成员上限（无界供给纪律）
     */
    static final int INTEREST_TAGS_MAX = 8;

    /**
     * 事件通道当日好感累计（进程内防操纵闸——Code Review P3 补强：刷屏 @ 单日事件
     * 累计不得超出统计通道日上限口径 dailySedimentMax；重启清零只回到无闸现状不放大
     * 风险，不新增 Redis 键——3.2.2 用途清单纪律）
     */
    private final Map<String, int[]> dailyEventAccumulated = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 日累计闸的内存护栏（超限整体重置——防操纵闸精度损失可接受）
     */
    private static final int DAILY_ACCUMULATOR_MAX_ENTRIES = 10_000;

    private final UserProfileMapper profileMapper;
    private final MemoryEntryMapper entryMapper;
    private final SedimentTranscriptMapper transcriptMapper;
    private final SystemParamService systemParams;
    private final java.time.Clock clock;

    public AffinityServiceImpl(UserProfileMapper profileMapper,
                               MemoryEntryMapper entryMapper,
                               SedimentTranscriptMapper transcriptMapper,
                               SystemParamService systemParams,
                               java.time.Clock clock) {
        this.profileMapper = profileMapper;
        this.entryMapper = entryMapper;
        this.transcriptMapper = transcriptMapper;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    @Override
    public AttitudeView view(GroupKey group, String userId) {
        UserProfileDO profile = profileMapper.selectByGroupAndUser(
                group.platform(), group.botId(), group.groupId(), userId);
        if (profile == null) {
            return AttitudeView.empty();
        }
        AffinityParams params = AffinityParams.load(systemParams);
        int effective = lazyDecayed(profile, params);
        return new AttitudeView(effective, AffinityService.tierOf(effective),
                profile.getFamiliarity() == null ? 0 : profile.getFamiliarity(),
                profile.getCallName(), profile.getPlatformName(), profile.getInteractDigest());
    }

    /**
     * 好感懒衰减：raw × 0.5^(空闲天数 / 半衰期)——喜欢与讨厌都随长期无互动淡化，向 0 回归
     */
    private int lazyDecayed(UserProfileDO profile, AffinityParams params) {
        int raw = profile.getAffinity() == null ? 0 : profile.getAffinity();
        if (raw == 0 || profile.getUpdatedAt() == null) {
            return raw;
        }
        double idleDays = elapsedDays(profile.getUpdatedAt(), LocalDateTime.now(clock));
        double factor = Math.pow(0.5, idleDays / params.halfLifeDays());
        return (int) Math.round(raw * factor);
    }

    private double elapsedDays(LocalDateTime from, LocalDateTime now) {
        return Math.max(0, Duration.between(from, now).toMillis() / (24.0 * 3600 * 1000));
    }

    @Override
    public void applyEventDelta(GroupKey group, String userId,
                                int familiarityDelta, int affinityDelta) {
        AffinityParams params = AffinityParams.load(systemParams);
        // 熟悉度只增不衰减：负调用直接归零（结构保证——不存在减熟悉度的入口）
        int fam = clamp(Math.max(0, familiarityDelta), 0, params.eventDeltaRangeMax());
        // 好感双向：增长渐进（钳 1~3）；下降按系数加速（记仇也是趣味性）后同受幅度钳制
        int aff;
        if (affinityDelta > 0) {
            aff = clamp(affinityDelta, params.eventDeltaRangeMin(), params.eventDeltaRangeMax());
        } else if (affinityDelta < 0) {
            double accelerated = affinityDelta * params.decreaseFactor();
            aff = (int) Math.round(clamp(accelerated, -params.eventDeltaRangeMax(), -params.eventDeltaRangeMin()));
        } else {
            return;   // 零幅度不落账
        }
        // 防操纵日累计闸：事件通道当日同方向累计超出统计通道日上限即拒（不落账）
        if (!withinDailyBudget(group, userId, aff, params)) {
            return;
        }
        profileMapper.applyDeltas(group.platform(), group.botId(), group.groupId(),
                userId, fam, aff, null);
    }

    /**
     * 事件通道当日累计闸：正负方向各自按 dailySedimentMax 封顶（与统计通道同值口径——
     * 单日好感净推进总量受限，刷屏 @ 或刷负都只能推动有限幅度）
     */
    private boolean withinDailyBudget(GroupKey group, String userId, int aff, AffinityParams params) {
        String key = group.asMapKey() + ":" + userId + ":"
                + java.time.LocalDate.now(clock).toEpochDay();
        int[] accumulated = dailyEventAccumulated.computeIfAbsent(key, k -> new int[1]);
        int already = aff > 0 ? accumulated[0] : -accumulated[0];
        if (already + Math.abs(aff) > params.dailySedimentMax()) {
            log.warn("好感事件通道日累计封顶拒绝 group={} user={} 当日累计={} delta={}",
                    group.groupId(), userId, already, aff);
            return false;
        }
        accumulated[0] += aff;
        if (dailyEventAccumulated.size() > DAILY_ACCUMULATOR_MAX_ENTRIES) {
            dailyEventAccumulated.clear();   // 内存护栏（闸精度损失可接受）
        }
        return true;
    }

    @Override
    public void runDailySedimentStatistics() {
        List<SedimentGroupDO> groups = transcriptMapper.selectDistinctGroups();
        for (SedimentGroupDO group : groups) {
            try {
                runDailySedimentStatisticsForGroup(new GroupKey(
                        group.getPlatform(), group.getBotId(), group.getGroupId()));
            } catch (Exception e) {
                log.warn("画像统计单群失败（跳过，下轮重试）group={}：{}",
                        group.getGroupId(), e.getMessage());
            }
        }
    }

    @Override
    public void runDailySedimentStatisticsForGroup(GroupKey group) {
        AffinityParams params = AffinityParams.load(systemParams);
        LocalDateTime since = LocalDateTime.now(clock).minus(STATISTICS_WINDOW);
        List<SedimentTranscriptDO> entries = transcriptMapper.selectUserSince(
                group.platform(), group.botId(), group.groupId(), since, TRANSCRIPT_SCAN_LIMIT);

        // 结构化互动信号计数：发言条数（活跃即相识）与 @ bot 条数（陪伴推好感）
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (SedimentTranscriptDO entry : entries) {
            InteractionSignal signal = parseSignal(entry, group.botId());
            if (signal == null) {
                continue;
            }
            int[] pair = counts.computeIfAbsent(signal.senderUserId(), k -> new int[2]);
            pair[0] += 1;                    // speak
            if (signal.atBot()) {
                pair[1] += 1;                // atBot
            }
        }
        for (Map.Entry<String, int[]> perUser : counts.entrySet()) {
            int speak = perUser.getValue()[0];
            int atBot = perUser.getValue()[1];
            // 单日幅度钳制（防操纵四条之二）：好感日上限、熟悉度同上限——群友刷屏也只能攒这么多
            int famDelta = Math.min(speak, params.dailySedimentMax());
            int affDelta = Math.min(atBot, params.dailySedimentMax());
            if (famDelta <= 0 && affDelta <= 0) {
                continue;
            }
            profileMapper.applyDeltas(group.platform(), group.botId(), group.groupId(),
                    perUser.getKey(), famDelta, affDelta, null);
        }

        refreshInterestTags(group);
        log.info("画像统计完成 group={} 统计成员数={}", group.groupId(), counts.size());
    }

    /**
     * interest_tags 从偏好类记忆重算（机械提炼：偏好条目正文即标签——语义提炼
     * 已由沉淀抽取管线的 profileSignals.interests 即时落账，此处兜底全量重算）
     */
    private void refreshInterestTags(GroupKey group) {
        Map<String, Set<String>> tagsByUser = new LinkedHashMap<>();
        for (MemoryEntryDO entry : entryMapper.selectActiveByGroup(
                group.platform(), group.botId(), group.groupId())) {
            if (!MemoryEntryDO.TYPE_PREFERENCE.equals(entry.getMemoryType())
                    || entry.getUserId() == null || entry.getContent() == null) {
                continue;
            }
            Set<String> tags = tagsByUser.computeIfAbsent(entry.getUserId(), k -> new LinkedHashSet<>());
            if (tags.size() < INTEREST_TAGS_MAX) {
                tags.add(entry.getContent());
            }
        }
        for (Map.Entry<String, Set<String>> perUser : tagsByUser.entrySet()) {
            UserProfileDO profile = profileMapper.selectByGroupAndUser(
                    group.platform(), group.botId(), group.groupId(), perUser.getKey());
            if (profile == null) {
                continue;
            }
            String tagsJson = JsonMappers.valueToStringOrNull(new ArrayList<>(perUser.getValue()));
            profileMapper.updateFields(profile.getId(), null, tagsJson, null, null, null);
        }
    }

    /**
     * 转录条目的结构化互动信号（与 ExtractionService.projectLine 同构的平铺 JSON 口径）：
     * 只取 senderUserId 与 at 段——零文本语义解析是防操纵的结构保证
     */
    private InteractionSignal parseSignal(SedimentTranscriptDO entry, String botId) {
        if (!SedimentTranscriptDO.KIND_USER.equals(entry.getKind())) {
            return null;
        }
        try {
            JsonNode payload = JsonMappers.shared().readTree(entry.getPayload());
            String sender = payload.path("senderUserId").asText(null);
            if (sender == null || sender.isBlank()) {
                return null;
            }
            boolean atBot = false;
            for (JsonNode part : payload.path("parts")) {
                if ("at".equals(part.path("part").asText(""))
                        && botId.equals(part.path("targetUserId").asText(""))) {
                    atBot = true;
                    break;
                }
            }
            return new InteractionSignal(sender, atBot);
        } catch (Exception e) {
            return null;   // 坏条目跳过不杀批
        }
    }

    private record InteractionSignal(String senderUserId, boolean atBot) {
    }

    private static int clamp(double value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }
}
