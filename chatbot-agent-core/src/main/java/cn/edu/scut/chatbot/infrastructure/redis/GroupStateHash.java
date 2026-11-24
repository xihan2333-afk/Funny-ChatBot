package cn.edu.scut.chatbot.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * 群状态 Hash（契约 redis-streams-agent-core.md 第 3 节）：
 * 键 chatbot:group-state:{platform}:{botId}:{groupId}。
 *
 * <p>本 feature 只读写 L0 闸门与自主调度所需的最小字段集——近期活跃时间戳、
 * Bot 近期发言计数（固定 60 秒窗口内的条数）。完整群状态对象的事件驱动维护归 2.4；
 * 本 feature 不新增 Redis 用途。
 */
@Component
public class GroupStateHash {

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

    private static final long RATE_WINDOW_MILLIS = 60_000;

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

    private String keyOf(String platform, String botId, String groupId) {
        return "chatbot:group-state:" + platform + ":" + botId + ":" + groupId;
    }
}
