package cn.edu.scut.chatbot.memory.social;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.context.window.TokenEstimator;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 群状态服务实现。纪律落位：六状态项全部纯计算零模型；Redis 失败读写都降级告警不抛
 * （读回空快照、写丢弃——内存态崩溃不心疼，下次事件或 rebuild 恢复）；静默时长读取时
 * 扣群暂停累计（暂停期间静默不增长——与情绪懒衰减同一顺延算法）。
 */
@Component
public class GroupStateServiceImpl implements GroupStateService {

    private static final Logger log = LoggerFactory.getLogger(GroupStateServiceImpl.class);

    /**
     * 摘要 token 上限（推送摘要 300 上限不做无界供给）
     */
    static final int DIGEST_TOKEN_BUDGET = 300;

    /**
     * 活跃度分档口径（与 DailyProfileStatistics 的画像分档一致：≥20 活跃 / ≥5 一般 / <5 安静）
     */
    private static final int ACTIVE_RATE_THRESHOLD = 20;
    private static final int MODERATE_RATE_THRESHOLD = 5;

    private final GroupStateHash groupStateHash;
    private final GroupMapper groupMapper;
    private final AgentSessionMapper sessionMapper;
    private final SessionManager sessionManager;
    private final Clock clock;

    public GroupStateServiceImpl(GroupStateHash groupStateHash,
                                 GroupMapper groupMapper,
                                 AgentSessionMapper sessionMapper,
                                 SessionManager sessionManager,
                                 Clock clock) {
        this.groupStateHash = groupStateHash;
        this.groupMapper = groupMapper;
        this.sessionMapper = sessionMapper;
        this.sessionManager = sessionManager;
        this.clock = clock;
    }

    @Override
    public void onGroupMessage(GroupKey group, String senderUserId, String text) {
        try {
            groupStateHash.recordGroupMessage(
                    group.platform(), group.botId(), group.groupId(), senderUserId, clock.millis());
        } catch (Exception e) {
            log.warn("群状态更新失败降级（内存态可重建）group={}：{}", group.groupId(), e.getMessage());
        }
    }

    @Override
    public void onBotSend(GroupKey group) {
        try {
            groupStateHash.recordBotSend(group.platform(), group.botId(), group.groupId());
            groupStateHash.recordAgentSend24h(group.platform(), group.botId(), group.groupId(), clock.millis());
        } catch (Exception e) {
            log.warn("Agent 发言记账失败降级 group={}：{}", group.groupId(), e.getMessage());
        }
    }

    @Override
    public void onCompactionRefreshed(GroupKey group, String topicDigest, String vibe) {
        try {
            groupStateHash.updateVibe(group.platform(), group.botId(), group.groupId(), vibe);
        } catch (Exception e) {
            log.warn("氛围刷新失败降级 group={}：{}", group.groupId(), e.getMessage());
        }
    }

    @Override
    public GroupStateSnapshot read(GroupKey group) {
        try {
            long lastMsgAt = groupStateHash.lastMsgAt(group.platform(), group.botId(), group.groupId());
            long silenceMillis = lastMsgAt == 0 ? 0
                    : Math.max(0, clock.millis() - lastMsgAt - pausedAdjustedMillis(group));
            return new GroupStateSnapshot(
                    silenceMillis,
                    groupStateHash.messageRateCount(group.platform(), group.botId(), group.groupId()),
                    groupStateHash.topicSnapshot(group.platform(), group.botId(), group.groupId()),
                    groupStateHash.vibe(group.platform(), group.botId(), group.groupId()),
                    groupStateHash.agentSend24hCount(group.platform(), group.botId(), group.groupId()),
                    groupStateHash.activeMembersRecent(group.platform(), group.botId(), group.groupId()).size());
        } catch (Exception e) {
            log.warn("群状态读取失败降级（返回空快照——调度器按不破冰处理）group={}：{}",
                    group.groupId(), e.getMessage());
            return GroupStateSnapshot.empty();
        }
    }

    @Override
    public void rebuild(GroupKey group) {
        try {
            AgentSessionDO session = sessionMapper.selectByGroup(
                    group.platform(), group.botId(), group.groupId());
            if (session == null) {
                return;
            }
            Instant now = clock.instant();
            Instant since24h = now.minus(Duration.ofHours(24));
            Instant since1h = now.minus(Duration.ofHours(1));

            Instant lastMsgAt = null;
            Instant msgRateWindowStart = null;
            int msgRateCount = 0;
            Instant agentWindowStart = null;
            int agentCount = 0;
            Map<String, Instant> memberLastSeen = new LinkedHashMap<>();

            for (AgentMessage message : sessionManager.loadTranscript(session)) {
                Instant occurredAt = message.occurredAt();
                if (occurredAt == null || occurredAt.isBefore(since24h)) {
                    continue;
                }
                if (message instanceof UserMessage user) {
                    lastMsgAt = occurredAt;
                    if (!occurredAt.isBefore(since1h)) {
                        msgRateWindowStart = msgRateWindowStart == null
                                ? occurredAt : msgRateWindowStart;
                        msgRateCount++;
                    }
                    if (user.senderUserId() != null && !user.senderUserId().isBlank()) {
                        memberLastSeen.put(user.senderUserId(), occurredAt);
                    }
                } else if (message instanceof AssistantMessage) {
                    agentWindowStart = agentWindowStart == null ? occurredAt : agentWindowStart;
                    agentCount++;
                }
            }

            groupStateHash.rewriteLastMsgAt(group.platform(), group.botId(), group.groupId(),
                    lastMsgAt == null ? 0 : lastMsgAt.toEpochMilli());
            if (msgRateWindowStart == null) {
                groupStateHash.rewriteMsgRate(group.platform(), group.botId(), group.groupId(),
                        now.toEpochMilli(), 0);
            } else {
                groupStateHash.rewriteMsgRate(group.platform(), group.botId(), group.groupId(),
                        msgRateWindowStart.toEpochMilli(), msgRateCount);
            }
            if (agentWindowStart == null) {
                groupStateHash.rewriteAgentSend24h(group.platform(), group.botId(), group.groupId(),
                        now.toEpochMilli(), 0);
            } else {
                groupStateHash.rewriteAgentSend24h(group.platform(), group.botId(), group.groupId(),
                        agentWindowStart.toEpochMilli(), agentCount);
            }
            Map<String, Long> members = new LinkedHashMap<>();
            memberLastSeen.forEach((userId, seen) -> members.put(userId, seen.toEpochMilli()));
            groupStateHash.rewriteActiveMembers(group.platform(), group.botId(), group.groupId(), members);
            log.info("群状态从转录重建完成 group={} 24h消息速率={} 活跃成员={}",
                    group.groupId(), msgRateCount, members.size());
        } catch (Exception e) {
            log.warn("群状态重建失败降级 group={}：{}", group.groupId(), e.getMessage());
        }
    }

    @Override
    public String renderDigest(GroupStateSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        builder.append("群活跃度：").append(activityLabel(snapshot.msgRatePerHour()))
                .append("（近1小时 ").append(snapshot.msgRatePerHour()).append(" 条消息）\n");
        String topics = renderTopics(snapshot.topicSnapshot());
        if (topics != null) {
            builder.append("当前话题：").append(topics).append('\n');
        }
        if (snapshot.vibe() != null && !snapshot.vibe().isBlank()) {
            builder.append("近期氛围：").append(snapshot.vibe()).append('\n');
        }
        builder.append("近期发言频率：近24小时已发言 ").append(snapshot.agentSend24h()).append(" 条");
        String digest = builder.toString();
        if (TokenEstimator.estimate(digest) <= DIGEST_TOKEN_BUDGET) {
            return digest;
        }
        // 超预算按行截断（自描述——供给上限不做无界投放）
        StringBuilder bounded = new StringBuilder();
        for (String line : digest.split("\n")) {
            if (TokenEstimator.estimate(bounded + line) > DIGEST_TOKEN_BUDGET) {
                break;
            }
            if (bounded.length() > 0) {
                bounded.append('\n');
            }
            bounded.append(line);
        }
        return bounded.toString();
    }

    /**
     * 话题快照渲染：热度前若干话题「关键词（热度 N）」分号拼接；
     * 解析失败或无话题返回 null（转录不足时省略该行）
     */
    private String renderTopics(String topicSnapshotJson) {
        if (topicSnapshotJson == null || topicSnapshotJson.isBlank()) {
            return null;
        }
        try {
            JsonNode topics = JsonMappers.shared().readTree(topicSnapshotJson).path("topics");
            if (!topics.isArray() || topics.isEmpty()) {
                return null;
            }
            StringBuilder rendered = new StringBuilder();
            for (JsonNode topic : topics) {
                if (rendered.length() > 0) {
                    rendered.append("；");
                }
                JsonNode keywords = topic.path("keywords");
                StringBuilder keywordText = new StringBuilder();
                for (JsonNode keyword : keywords) {
                    if (keywordText.length() > 0) {
                        keywordText.append("、");
                    }
                    keywordText.append(keyword.asText());
                }
                rendered.append(keywordText).append("（热度 ").append(topic.path("count").asInt(0)).append("）");
                if (rendered.length() > 120) {
                    break;   // 单行长度护栏
                }
            }
            return rendered.isEmpty() ? null : rendered.toString();
        } catch (Exception e) {
            log.warn("话题快照渲染解析失败省略话题行：{}", e.getMessage());
            return null;
        }
    }

    private String activityLabel(int msgRatePerHour) {
        if (msgRatePerHour >= ACTIVE_RATE_THRESHOLD) {
            return "活跃";
        }
        if (msgRatePerHour >= MODERATE_RATE_THRESHOLD) {
            return "一般";
        }
        return "安静";
    }

    /**
     * 群暂停顺延：已累计暂停时长 + 当前进行中的暂停段（与 EmotionServiceImpl 同算法——
     * 暂停期间静默不增长，恢复后计时顺延）
     */
    private long pausedAdjustedMillis(GroupKey group) {
        GroupDO groupRow = groupMapper.selectByGroup(
                group.platform(), group.botId(), group.groupId());
        if (groupRow == null) {
            return 0;
        }
        long accumulated = groupRow.getPausedAccumulatedMillis() == null
                ? 0 : groupRow.getPausedAccumulatedMillis();
        if (groupRow.getPausedAt() != null) {
            accumulated += Math.max(0, Duration.between(
                    groupRow.getPausedAt(), java.time.LocalDateTime.now(clock)).toMillis());
        }
        return accumulated;
    }
}
