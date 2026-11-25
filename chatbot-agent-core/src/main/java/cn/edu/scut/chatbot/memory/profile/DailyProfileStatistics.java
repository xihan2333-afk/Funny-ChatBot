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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 每日画像统计（T030）：纯计算刷新群体画像与成员关系——零模型调用
 * （负向断言口径：统计全程不触模型网关）。
 *
 * <p>口径拍板（tasks.md 未细化处的机械形态，收尾报告记录）：
 * <ul>
 *   <li>activity_level 四档按当日发言条数：活跃 ≥20 / 一般 5~19 / 安静 1~4；当日零发言不动；</li>
 *   <li>vibe_baseline 按当日群消息总量三档：热闹 ≥200 / 一般 ≥50 / 安静——
 *       「爱抬杠」是语义判定，纯代码不做（语义层归沉淀抽取管线）；</li>
 *   <li>topic_dist 从 topic 类 active 记忆条目按正文计数（记忆是话题的结构化事实源）；</li>
 *   <li>member_relation 只记结构信号：消息内 @ 了哪位群友就与谁累计一条 interact 边
 *       （a/b 写入前字典序归一）；tease / avoid 是语义判定，不在本通道。</li>
 * </ul>
 */
@Component
public class DailyProfileStatistics {

    private static final Logger log = LoggerFactory.getLogger(DailyProfileStatistics.class);

    static final Duration STATISTICS_WINDOW = Duration.ofDays(1);
    static final int TRANSCRIPT_SCAN_LIMIT = 5000;

    static final String ACTIVITY_ACTIVE = "活跃";
    static final String ACTIVITY_NORMAL = "一般";
    static final String ACTIVITY_QUIET = "安静";

    static final int ACTIVITY_ACTIVE_THRESHOLD = 20;
    static final int ACTIVITY_NORMAL_THRESHOLD = 5;

    static final String VIBE_BUSY = "热闹";
    static final String VIBE_NORMAL = "一般";
    static final String VIBE_QUIET = "安静";

    static final int VIBE_BUSY_THRESHOLD = 200;
    static final int VIBE_NORMAL_THRESHOLD = 50;

    private final UserProfileMapper profileMapper;
    private final GroupProfileMapper groupProfileMapper;
    private final MemberRelationMapper relationMapper;
    private final MemoryEntryMapper entryMapper;
    private final SedimentTranscriptMapper transcriptMapper;
    private final SystemParamService systemParams;
    private final Clock clock;

    public DailyProfileStatistics(UserProfileMapper profileMapper,
                                  GroupProfileMapper groupProfileMapper,
                                  MemberRelationMapper relationMapper,
                                  MemoryEntryMapper entryMapper,
                                  SedimentTranscriptMapper transcriptMapper,
                                  SystemParamService systemParams,
                                  Clock clock) {
        this.profileMapper = profileMapper;
        this.groupProfileMapper = groupProfileMapper;
        this.relationMapper = relationMapper;
        this.entryMapper = entryMapper;
        this.transcriptMapper = transcriptMapper;
        this.systemParams = systemParams;
        this.clock = clock;
    }

    /**
     * 全群遍历（每日任务编排入口；单群失败跳过不杀批）
     */
    public void runDaily() {
        for (SedimentGroupDO group : transcriptMapper.selectDistinctGroups()) {
            try {
                refreshForGroup(new GroupKey(group.getPlatform(), group.getBotId(), group.getGroupId()));
            } catch (Exception e) {
                log.warn("群体画像统计单群失败 group={}：{}", group.getGroupId(), e.getMessage());
            }
        }
    }

    /**
     * 单群刷新（IT 可直驱）
     */
    public void refreshForGroup(GroupKey group) {
        LocalDateTime since = LocalDateTime.now(clock).minus(STATISTICS_WINDOW);
        List<SedimentTranscriptDO> entries = transcriptMapper.selectUserSince(
                group.platform(), group.botId(), group.groupId(), since, TRANSCRIPT_SCAN_LIMIT);

        Map<String, Integer> speakCountByUser = new LinkedHashMap<>();
        Map<Integer, Integer> hourCounts = new TreeMap<>();
        Map<String, Map<String, Integer>> interactEdges = new LinkedHashMap<>();
        for (SedimentTranscriptDO entry : entries) {
            MessageProjection message = project(entry);
            if (message == null) {
                continue;
            }
            speakCountByUser.merge(message.senderUserId(), 1, Integer::sum);
            hourCounts.merge(message.occurredAt().getHour(), 1, Integer::sum);
            for (String target : message.atTargets()) {
                if (target.equals(message.senderUserId())) {
                    continue;
                }
                interactEdges.computeIfAbsent(message.senderUserId(), k -> new LinkedHashMap<>())
                        .merge(target, 1, Integer::sum);
            }
        }

        refreshActivityLevels(group, speakCountByUser);
        refreshGroupProfile(group, entries.size(), hourCounts, speakCountByUser);
        accumulateRelations(group, interactEdges);
        log.info("群体画像统计完成 group={} 当日消息={} 成员={}",
                group.groupId(), entries.size(), speakCountByUser.size());
    }

    private void refreshActivityLevels(GroupKey group, Map<String, Integer> speakCountByUser) {
        for (Map.Entry<String, Integer> perUser : speakCountByUser.entrySet()) {
            UserProfileDO profile = profileMapper.selectByGroupAndUser(
                    group.platform(), group.botId(), group.groupId(), perUser.getKey());
            if (profile == null) {
                continue;
            }
            profileMapper.updateFields(profile.getId(), null, null, null,
                    activityLevelOf(perUser.getValue()), null);
        }
    }

    /**
     * 活跃度分档纯函数（分档边界单测锚点）：零发言由调用方过滤（不动既有档）
     */
    public static String activityLevelOf(int speakCount) {
        if (speakCount >= ACTIVITY_ACTIVE_THRESHOLD) {
            return ACTIVITY_ACTIVE;
        }
        if (speakCount >= ACTIVITY_NORMAL_THRESHOLD) {
            return ACTIVITY_NORMAL;
        }
        return ACTIVITY_QUIET;
    }

    public static String vibeOf(int dailyMessageCount) {
        if (dailyMessageCount >= VIBE_BUSY_THRESHOLD) {
            return VIBE_BUSY;
        }
        if (dailyMessageCount >= VIBE_NORMAL_THRESHOLD) {
            return VIBE_NORMAL;
        }
        return VIBE_QUIET;
    }

    private void refreshGroupProfile(GroupKey group, int dailyMessageCount,
                                     Map<Integer, Integer> hourCounts,
                                     Map<String, Integer> speakCountByUser) {
        Map<String, Integer> topicDist = new LinkedHashMap<>();
        for (MemoryEntryDO entry : entryMapper.selectActiveByGroup(
                group.platform(), group.botId(), group.groupId())) {
            if (MemoryEntryDO.TYPE_TOPIC.equals(entry.getMemoryType()) && entry.getContent() != null) {
                topicDist.merge(entry.getContent(), 1, Integer::sum);
            }
        }
        GroupProfileDO profile = new GroupProfileDO();
        profile.setPlatform(group.platform());
        profile.setBotId(group.botId());
        profile.setGroupId(group.groupId());
        profile.setActiveHours(JsonMappers.valueToStringOrNull(hourCounts));
        profile.setVibeBaseline(vibeOf(dailyMessageCount));
        profile.setTopicDist(JsonMappers.valueToStringOrNull(topicDist));
        // 活跃成员概况：当日发言数 Top 成员（粗粒度、不随发言人变化——群上下文层数据源）
        Map<String, Integer> topMembers = new LinkedHashMap<>();
        speakCountByUser.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(entry -> topMembers.put(entry.getKey(), entry.getValue()));
        profile.setActiveMembers(JsonMappers.valueToStringOrNull(topMembers));
        groupProfileMapper.upsertProfile(profile);
    }

    private void accumulateRelations(GroupKey group,
                                     Map<String, Map<String, Integer>> interactEdges) {
        for (Map.Entry<String, Map<String, Integer>> fromUser : interactEdges.entrySet()) {
            for (Map.Entry<String, Integer> toEdge : fromUser.getValue().entrySet()) {
                MemberRelationDO relation = new MemberRelationDO();
                relation.setPlatform(group.platform());
                relation.setBotId(group.botId());
                relation.setGroupId(group.groupId());
                // 字典序归一（无向边唯一化：a 恒为较小方）
                String a = fromUser.getKey();
                String b = toEdge.getKey();
                if (a.compareTo(b) > 0) {
                    String swap = a;
                    a = b;
                    b = swap;
                }
                relation.setUserIdA(a);
                relation.setUserIdB(b);
                relation.setRelationKind("interact");
                relation.setStrength(toEdge.getValue());
                relationMapper.upsertRelation(relation);
            }
        }
    }

    /**
     * 消息投影（与 ExtractionService.projectLine 同构的平铺 JSON 口径）：
     * 发言人、发生时刻、@ 目标清单（bot 除外——那是 AffinityService 的陪伴信号）
     */
    private MessageProjection project(SedimentTranscriptDO entry) {
        if (!SedimentTranscriptDO.KIND_USER.equals(entry.getKind()) || entry.getOccurredAt() == null) {
            return null;
        }
        try {
            JsonNode payload = JsonMappers.shared().readTree(entry.getPayload());
            String sender = payload.path("senderUserId").asText(null);
            if (sender == null || sender.isBlank()) {
                return null;
            }
            List<String> atTargets = new ArrayList<>();
            for (JsonNode part : payload.path("parts")) {
                if ("at".equals(part.path("part").asText(""))) {
                    atTargets.add(part.path("targetUserId").asText(""));
                }
            }
            return new MessageProjection(sender, entry.getOccurredAt(), atTargets);
        } catch (Exception e) {
            return null;
        }
    }

    private record MessageProjection(String senderUserId, LocalDateTime occurredAt,
                                     List<String> atTargets) {
    }
}
