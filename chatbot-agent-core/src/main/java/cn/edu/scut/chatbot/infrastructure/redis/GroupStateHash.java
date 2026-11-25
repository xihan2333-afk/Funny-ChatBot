package cn.edu.scut.chatbot.infrastructure.redis;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 群状态 Hash（契约 redis-streams-agent-core.md 第 3 节）：
 * 键 chatbot:group-state:{platform}:{botId}:{groupId}。
 *
 * <p>字段分两组：L0 闸门与频控四字段（lastActiveAt / sendWindowStart / sendCount /
 * lastSendAt——002 既有，语义保留职责不变）；004 社交行为六状态项（T049 扩容——
 * 最近群消息、消息速率滑动窗口、话题快照、氛围、Agent 24h 发言统计、活跃成员）。
 * 同一 Hash 键不新增 Redis 数据结构（3.2.2 用途清单纪律）；TTL 7 天沿用；
 * JSON 字段显式 Jackson（String 值形态）。
 */
@Component
public class GroupStateHash {

    private static final Logger log = LoggerFactory.getLogger(GroupStateHash.class);

    /**
     * 近期活跃时间戳（epoch 毫秒）——调度器静默判定输入
     */
    static final String FIELD_LAST_ACTIVE_AT = "lastActiveAt";

    /**
     * Bot 发言计数窗口起点（epoch 毫秒）——频控 60 秒滚动窗口
     */
    static final String FIELD_SEND_WINDOW_START = "sendWindowStart";

    /**
     * Bot 窗口内已发言条数——频控计数
     */
    static final String FIELD_SEND_COUNT = "sendCount";

    /**
     * Bot 最近一次发言时刻（epoch 毫秒）——冷却判定输入
     */
    static final String FIELD_LAST_SEND_AT = "lastSendAt";

    /**
     * 最近一条群消息时间（epoch 毫秒）——社交静默时长判定（区别 lastActiveAt：
     * 后者任何触发器都刷新，前者只有群友消息）
     */
    static final String FIELD_LAST_MSG_AT = "lastMsgAt";

    /**
     * 消息速率滑动窗口起点（epoch 毫秒）——近 1 小时
     */
    static final String FIELD_MSG_RATE_WINDOW_START = "msgRateWindowStart";

    /**
     * 消息速率窗口内群消息条数
     */
    static final String FIELD_MSG_RATE_COUNT = "msgRateCount";

    /**
     * 当前话题快照 JSON（话题热度计数与时间——TopicLifecycleService 维护）
     */
    static final String FIELD_TOPIC_SNAPSHOT = "topicSnapshot";

    /**
     * 最近摘要氛围段文本（压缩完成时刷新）
     */
    static final String FIELD_VIBE = "vibe";

    /**
     * Agent 24h 发言统计窗口起点（epoch 毫秒）——策略统计（与频控 sendCount 分立：
     * 前者供主动开口策略、后者是 L0 频控闸门）
     */
    static final String FIELD_AGENT_SEND_24H_START = "agentSend24hStart";

    /**
     * Agent 24h 窗口内发言条数
     */
    static final String FIELD_AGENT_SEND_24H_COUNT = "agentSend24hCount";

    /**
     * 活跃成员 JSON（userId → lastSeenAt epoch 毫秒；读取时裁剪 24h 窗口）
     */
    static final String FIELD_ACTIVE_MEMBERS = "activeMembers";

    private static final long RATE_WINDOW_MILLIS = 60_000;

    /**
     * 消息速率滑动窗口：近 1 小时
     */
    static final long MSG_RATE_WINDOW_MILLIS = Duration.ofHours(1).toMillis();

    /**
     * Agent 发言统计窗口：近 24 小时
     */
    static final long AGENT_SEND_WINDOW_MILLIS = Duration.ofHours(24).toMillis();

    /**
     * 活跃成员保留窗口：近 24 小时（读取时裁剪，不回写）
     */
    static final long ACTIVE_MEMBER_WINDOW_MILLIS = Duration.ofHours(24).toMillis();

    private static final long TTL_MILLIS = Duration.ofDays(7).toMillis();

    private final StringRedisTemplate redisTemplate;

    private final Clock clock;

    public GroupStateHash(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    /**
     * 群有新事件：刷新近期活跃时间戳
     */
    public void touchActive(String platform, String botId, String groupId) {
        String key = keyOf(platform, botId, groupId);
        redisTemplate.opsForHash().put(key, FIELD_LAST_ACTIVE_AT, String.valueOf(clock.millis()));
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 近期活跃时间戳（无记录返回 0——调度器按从未活跃处理）
     */
    public long lastActiveAt(String platform, String botId, String groupId) {
        Object value = redisTemplate.opsForHash().get(keyOf(platform, botId, groupId), FIELD_LAST_ACTIVE_AT);
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    /**
     * Bot 发言记账：刷新最近发言时刻；60 秒滚动窗口内计数（过窗重置）
     */
    public void recordBotSend(String platform, String botId, String groupId) {
        String key = keyOf(platform, botId, groupId);
        long now = clock.millis();
        redisTemplate.opsForHash().put(key, FIELD_LAST_SEND_AT, String.valueOf(now));
        Object windowStart = redisTemplate.opsForHash().get(key, FIELD_SEND_WINDOW_START);
        long window = windowStart == null ? 0 : Long.parseLong(String.valueOf(windowStart));
        if (now - window >= RATE_WINDOW_MILLIS) {
            redisTemplate.opsForHash().put(key, FIELD_SEND_WINDOW_START, String.valueOf(now));
            redisTemplate.opsForHash().put(key, FIELD_SEND_COUNT, "1");
        } else {
            redisTemplate.opsForHash().increment(key, FIELD_SEND_COUNT, 1);
        }
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 冷却剩余毫秒（距最近一次发言未满冷却期则返回正数；无发言记录返回 0——不冷却）
     */
    public long cooldownRemainingMillis(String platform, String botId, String groupId, long cooldownMillis) {
        Object lastSend = redisTemplate.opsForHash().get(keyOf(platform, botId, groupId), FIELD_LAST_SEND_AT);
        if (lastSend == null) {
            return 0;
        }
        long elapsed = clock.millis() - Long.parseLong(String.valueOf(lastSend));
        return Math.max(0, cooldownMillis - elapsed);
    }

    /**
     * 60 秒窗口内 Bot 已发言条数（频控判定输入）
     */
    public int sendCountInWindow(String platform, String botId, String groupId) {
        String key = keyOf(platform, botId, groupId);
        Object windowStart = redisTemplate.opsForHash().get(key, FIELD_SEND_WINDOW_START);
        Object count = redisTemplate.opsForHash().get(key, FIELD_SEND_COUNT);
        if (windowStart == null || count == null) {
            return 0;
        }
        long window = Long.parseLong(String.valueOf(windowStart));
        if (clock.millis() - window >= RATE_WINDOW_MILLIS) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(count));
    }

    /**
     * 群销毁时清理状态（历史沉淀策略由群配置决定，Hash 状态直接清）
     */
    public void deleteGroupState(String platform, String botId, String groupId) {
        redisTemplate.delete(keyOf(platform, botId, groupId));
    }

    // =====================================================================
    // 004 社交行为六状态项（T049 扩容）——全部纯计算事件驱动，语义见 GroupStateService
    // =====================================================================

    /**
     * 群消息记账（GroupStateService.onGroupMessage 驱动）：刷新 lastMsgAt、
     * 累加近 1 小时消息速率窗口（过窗重置）、维护活跃成员 lastSeenAt。
     * 调用点在同群串行执行器内，读改写无并发竞争。
     */
    public void recordGroupMessage(String platform, String botId, String groupId,
                                   String senderUserId, long occurredAtMillis) {
        String key = keyOf(platform, botId, groupId);
        long now = clock.millis();
        redisTemplate.opsForHash().put(key, FIELD_LAST_MSG_AT, String.valueOf(now));
        Object windowStart = redisTemplate.opsForHash().get(key, FIELD_MSG_RATE_WINDOW_START);
        long window = windowStart == null ? 0 : Long.parseLong(String.valueOf(windowStart));
        if (now - window >= MSG_RATE_WINDOW_MILLIS) {
            redisTemplate.opsForHash().put(key, FIELD_MSG_RATE_WINDOW_START, String.valueOf(now));
            redisTemplate.opsForHash().put(key, FIELD_MSG_RATE_COUNT, "1");
        } else {
            redisTemplate.opsForHash().increment(key, FIELD_MSG_RATE_COUNT, 1);
        }
        if (senderUserId != null && !senderUserId.isBlank()) {
            Map<String, Long> members = readActiveMembers(key);
            members.put(senderUserId, now);
            writeActiveMembers(key, members);
        }
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 最近一条群消息时间（无记录返回 0）
     */
    public long lastMsgAt(String platform, String botId, String groupId) {
        Object value = redisTemplate.opsForHash().get(keyOf(platform, botId, groupId), FIELD_LAST_MSG_AT);
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    /**
     * 近 1 小时窗口内群消息条数（窗口已过归零——读取时衰减语义）
     */
    public int messageRateCount(String platform, String botId, String groupId) {
        String key = keyOf(platform, botId, groupId);
        Object windowStart = redisTemplate.opsForHash().get(key, FIELD_MSG_RATE_WINDOW_START);
        Object count = redisTemplate.opsForHash().get(key, FIELD_MSG_RATE_COUNT);
        if (windowStart == null || count == null) {
            return 0;
        }
        long window = Long.parseLong(String.valueOf(windowStart));
        if (clock.millis() - window >= MSG_RATE_WINDOW_MILLIS) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(count));
    }

    /**
     * 话题快照覆写（TopicLifecycleService 维护——热度计数与冷却计次的唯一载体）
     */
    public void updateTopicSnapshot(String platform, String botId, String groupId, String snapshotJson) {
        String key = keyOf(platform, botId, groupId);
        redisTemplate.opsForHash().put(key, FIELD_TOPIC_SNAPSHOT, snapshotJson);
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 话题快照原文（无记录返回 null）
     */
    public String topicSnapshot(String platform, String botId, String groupId) {
        Object value = redisTemplate.opsForHash().get(keyOf(platform, botId, groupId), FIELD_TOPIC_SNAPSHOT);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 氛围覆写（压缩完成时刷新——最近摘要氛围段）
     */
    public void updateVibe(String platform, String botId, String groupId, String vibe) {
        String key = keyOf(platform, botId, groupId);
        redisTemplate.opsForHash().put(key, FIELD_VIBE, vibe == null ? "" : vibe);
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 最近摘要氛围段（无记录返回 null）
     */
    public String vibe(String platform, String botId, String groupId) {
        Object value = redisTemplate.opsForHash().get(keyOf(platform, botId, groupId), FIELD_VIBE);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /**
     * Agent 发言 24h 统计记账（GroupStateService.onBotSend 驱动——策略统计通道，
     * 与频控的 recordBotSend 分立口径）
     */
    public void recordAgentSend24h(String platform, String botId, String groupId, long occurredAtMillis) {
        String key = keyOf(platform, botId, groupId);
        long now = clock.millis();
        Object windowStart = redisTemplate.opsForHash().get(key, FIELD_AGENT_SEND_24H_START);
        long window = windowStart == null ? 0 : Long.parseLong(String.valueOf(windowStart));
        if (now - window >= AGENT_SEND_WINDOW_MILLIS) {
            redisTemplate.opsForHash().put(key, FIELD_AGENT_SEND_24H_START, String.valueOf(now));
            redisTemplate.opsForHash().put(key, FIELD_AGENT_SEND_24H_COUNT, "1");
        } else {
            redisTemplate.opsForHash().increment(key, FIELD_AGENT_SEND_24H_COUNT, 1);
        }
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 近 24 小时 Agent 发言条数（窗口已过归零）
     */
    public int agentSend24hCount(String platform, String botId, String groupId) {
        String key = keyOf(platform, botId, groupId);
        Object windowStart = redisTemplate.opsForHash().get(key, FIELD_AGENT_SEND_24H_START);
        Object count = redisTemplate.opsForHash().get(key, FIELD_AGENT_SEND_24H_COUNT);
        if (windowStart == null || count == null) {
            return 0;
        }
        long window = Long.parseLong(String.valueOf(windowStart));
        if (clock.millis() - window >= AGENT_SEND_WINDOW_MILLIS) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(count));
    }

    /**
     * 近 24 小时活跃成员（userId → lastSeenAt 毫秒）——读取时裁剪窗口外成员，不回写
     */
    public Map<String, Long> activeMembersRecent(String platform, String botId, String groupId) {
        return readActiveMembers(keyOf(platform, botId, groupId));
    }

    /**
     * 活跃成员覆写（rebuild 重建路径用——整图重写）
     */
    public void rewriteActiveMembers(String platform, String botId, String groupId,
                                     Map<String, Long> members) {
        String key = keyOf(platform, botId, groupId);
        writeActiveMembers(key, members == null ? Map.of() : members);
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 消息速率窗口直写（rebuild 重建路径用）
     */
    public void rewriteMsgRate(String platform, String botId, String groupId,
                               long windowStartMillis, int count) {
        String key = keyOf(platform, botId, groupId);
        redisTemplate.opsForHash().put(key, FIELD_MSG_RATE_WINDOW_START, String.valueOf(windowStartMillis));
        redisTemplate.opsForHash().put(key, FIELD_MSG_RATE_COUNT, String.valueOf(count));
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * Agent 24h 统计直写（rebuild 重建路径用）
     */
    public void rewriteAgentSend24h(String platform, String botId, String groupId,
                                    long windowStartMillis, int count) {
        String key = keyOf(platform, botId, groupId);
        redisTemplate.opsForHash().put(key, FIELD_AGENT_SEND_24H_START, String.valueOf(windowStartMillis));
        redisTemplate.opsForHash().put(key, FIELD_AGENT_SEND_24H_COUNT, String.valueOf(count));
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * lastMsgAt 直写（rebuild 重建路径用）
     */
    public void rewriteLastMsgAt(String platform, String botId, String groupId, long lastMsgAtMillis) {
        String key = keyOf(platform, botId, groupId);
        redisTemplate.opsForHash().put(key, FIELD_LAST_MSG_AT, String.valueOf(lastMsgAtMillis));
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    private Map<String, Long> readActiveMembers(String key) {
        Object raw = redisTemplate.opsForHash().get(key, FIELD_ACTIVE_MEMBERS);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Long> members = new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = JsonMappers.shared().readValue(
                    String.valueOf(raw),
                    JsonMappers.shared().getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, Object.class));
            long cutoff = clock.millis() - ACTIVE_MEMBER_WINDOW_MILLIS;
            parsed.forEach((userId, lastSeen) -> {
                long lastSeenAt = lastSeen == null ? 0 : Long.parseLong(String.valueOf(lastSeen));
                if (lastSeenAt >= cutoff) {
                    members.put(userId, lastSeenAt);
                }
            });
        } catch (Exception e) {
            log.warn("activeMembers JSON 解析失败按空处理：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
        return members;
    }

    private void writeActiveMembers(String key, Map<String, Long> members) {
        long cutoff = clock.millis() - ACTIVE_MEMBER_WINDOW_MILLIS;
        Map<String, Long> bounded = new LinkedHashMap<>();
        members.forEach((userId, lastSeenAt) -> {
            if (lastSeenAt >= cutoff) {
                bounded.put(userId, lastSeenAt);
            }
        });
        try {
            redisTemplate.opsForHash().put(key, FIELD_ACTIVE_MEMBERS,
                    JsonMappers.shared().writeValueAsString(bounded));
        } catch (Exception e) {
            log.warn("activeMembers JSON 序列化失败丢弃本次更新：{}", e.getMessage());
        }
    }

    private String keyOf(String platform, String botId, String groupId) {
        return "chatbot:group-state:" + platform + ":" + botId + ":" + groupId;
    }
}
