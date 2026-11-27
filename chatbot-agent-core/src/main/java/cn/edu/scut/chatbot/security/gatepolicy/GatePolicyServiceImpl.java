package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link GatePolicyService} 实现：黑名单 / 群开关 DB 直查（调用频率低不缓存，
 * 改表即生效）；域名黑名单走 system_param（10 秒热刷新）；频控计数 Redis 窗口键
 * 当日零点过期（data-model 3.2——FixedClock 拨钟可测跨日重置）。
 */
@Component
public class GatePolicyServiceImpl implements GatePolicyService {

    private static final Logger log = LoggerFactory.getLogger(GatePolicyServiceImpl.class);

    static final String PARAM_DOMAIN_BLACKLIST = "security.domain.blacklist";
    static final String PARAM_HEAVY_PER_USER = "security.freq.heavyTaskPerUserDaily";
    static final String PARAM_HEAVY_PER_GROUP = "security.freq.heavyTaskPerGroupDaily";
    static final String PARAM_RUNCODE_PER_GROUP = "security.freq.runCodePerGroupDaily";
    static final String PARAM_MANDATE_DENY_PER_HOUR = "security.freq.mandateDenyPerHour";

    private static final java.time.format.DateTimeFormatter HOUR_BUCKET =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final BlacklistMapper blacklistMapper;
    private final GroupMapper groupMapper;
    private final SystemParamService systemParams;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public GatePolicyServiceImpl(BlacklistMapper blacklistMapper,
                                 GroupMapper groupMapper,
                                 SystemParamService systemParams,
                                 StringRedisTemplate redis,
                                 Clock clock) {
        this.blacklistMapper = blacklistMapper;
        this.groupMapper = groupMapper;
        this.systemParams = systemParams;
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean isBlacklisted(String platform, String botId, String groupId, String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            return blacklistMapper.selectHit(platform, botId, groupId, userId) != null;
        } catch (Exception e) {
            log.warn("黑名单查询失败按未命中处理（不因 DB 抖动误伤正常用户）", e);
            return false;
        }
    }

    @Override
    public boolean isGroupEnabled(String platform, String botId, String groupId) {
        try {
            GroupDO group = groupMapper.selectByGroup(platform, botId, groupId);
            return group != null && group.getInService() != null && group.getInService() == 1
                    && "ACTIVE".equals(group.getLifecycle());
        } catch (Exception e) {
            log.warn("群开关查询失败按未服务处理", e);
            return false;
        }
    }

    @Override
    public boolean isDomainBlocked(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        String raw = systemParams.getString(PARAM_DOMAIN_BLACKLIST, "[]");
        try {
            List<String> blocked = JsonMappers.shared().readValue(raw,
                    new TypeReference<List<String>>() {
                    });
            String target = domain.toLowerCase();
            for (String entry : blocked) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String normalized = entry.trim().toLowerCase();
                if (target.equals(normalized) || target.endsWith("." + normalized)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("域名黑名单解析失败按空清单处理", e);
            return false;
        }
    }

    @Override
    public FrequencyVerdict checkHeavyTaskQuota(String platform, String botId,
                                                String groupId, String userId) {
        long limit = systemParams.getInt(PARAM_HEAVY_PER_USER, 10);
        String date = LocalDate.now(clock).toString();
        String key = "freq:heavyTask:" + date + ":" + groupTriple(platform, botId, groupId) + ":" + userId;
        long used = countInWindow(key);
        return new FrequencyVerdict(used <= limit, used, limit, "heavyTask:user");
    }

    @Override
    public FrequencyVerdict checkHeavyTaskGroupQuota(String platform, String botId, String groupId) {
        long limit = systemParams.getInt(PARAM_HEAVY_PER_GROUP, 100);
        String date = LocalDate.now(clock).toString();
        String key = "freq:heavyTask:group:" + date + ":" + groupTriple(platform, botId, groupId);
        long used = countInWindow(key);
        return new FrequencyVerdict(used <= limit, used, limit, "heavyTask:group");
    }

    @Override
    public FrequencyVerdict checkRunCodeQuota(String platform, String botId, String groupId) {
        long limit = systemParams.getInt(PARAM_RUNCODE_PER_GROUP, 20);
        String date = LocalDate.now(clock).toString();
        String key = "freq:runCode:" + date + ":" + groupTriple(platform, botId, groupId);
        long used = countInWindow(key);
        return new FrequencyVerdict(used <= limit, used, limit, "runCode:group");
    }

    private static String groupTriple(String platform, String botId, String groupId) {
        return (platform != null ? platform : "") + ":" + (botId != null ? botId : "")
                + ":" + (groupId != null ? groupId : "");
    }

    @Override
    public FrequencyVerdict recordMandateDenied(String userId) {
        long threshold = systemParams.getInt(PARAM_MANDATE_DENY_PER_HOUR, 10);
        if (userId == null || userId.isBlank()) {
            return new FrequencyVerdict(true, 0, threshold, "mandate:hour");
        }
        String hourBucket = LocalDateTime.now(clock).format(HOUR_BUCKET);
        String key = "freq:mandate:" + userId + ":" + hourBucket;
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1L) {
            redis.expire(key, Duration.ofHours(2));
        }
        long count = used == null ? 0 : used;
        boolean underThreshold = count < threshold;
        if (!underThreshold) {
            log.warn("同用户高频越权尝试达到阈值 userId={} hourBucket={} count={} threshold={}",
                    userId, hourBucket, count, threshold);
        }
        return new FrequencyVerdict(underThreshold, count, threshold, "mandate:hour");
    }

    @Override
    @EventListener(cn.edu.scut.chatbot.tool.registry.ToolRegistry.ConfigRefreshEvent.class)
    public void refresh() {
        // 策略面全部实时读（DB 直查 / system_param 热刷新缓存）——事件到达无需动作，
        // 挂点是契约要求的热生效语义锚（后续若引入进程内缓存在此清）
    }

    /**
     * 窗口计数：increment 即占一次；首计数设置当日零点过期（拨钟改日期即新窗口）
     */
    private long countInWindow(String key) {
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1L) {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime midnight = LocalDate.now(clock).plusDays(1).atStartOfDay();
            Duration ttl = Duration.between(now, midnight);
            if (ttl.isZero() || ttl.isNegative()) {
                ttl = Duration.ofSeconds(1);
            }
            redis.expire(key, ttl);
        }
        return used == null ? 0 : used;
    }
}
