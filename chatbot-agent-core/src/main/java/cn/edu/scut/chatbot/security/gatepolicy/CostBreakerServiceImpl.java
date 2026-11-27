package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogAggregation;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link CostBreakerService} 实现（R15、FR-017）：
 * <ul>
 *   <li>分钟级定时聚合当日 {@code llm_call_log(is_replay=0)} 全局与分群成本，与
 *       {@code security.breaker.globalDailyLimit} / {@code groupDailyLimit} 比较</li>
 *   <li>Redis 标记键 {@code breaker:{date}:{scope}}（data-model 3.2），TTL 设到次日零点；
 *       当日熔断不自动解除（即使阈值调高，防临界抖动），仅次日自然过期或人工解除</li>
 *   <li>触发与解除都同步记录 {@code security_event(circuitBreak)}</li>
 * </ul>
 */
@Component
public class CostBreakerServiceImpl implements CostBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CostBreakerServiceImpl.class);

    static final String PARAM_GLOBAL_DAILY_LIMIT = "security.breaker.globalDailyLimit";
    static final String PARAM_GROUP_DAILY_LIMIT = "security.breaker.groupDailyLimit";

    private static final BigDecimal DEFAULT_GLOBAL_LIMIT = new BigDecimal("1000000");
    private static final BigDecimal DEFAULT_GROUP_LIMIT = new BigDecimal("100000");

    /**
     * 熔断时拦截的高耗能能力槽位（chat_main 明确不查，保留被动回应）
     */
    private static final Set<String> GATED_ABILITY_SLOTS = Set.of(
            ModelCatalog.SLOT_IMAGE_GEN,
            ModelCatalog.SLOT_VOICE_GEN
    );

    private final LlmCallLogMapper callLogMapper;
    private final SystemParamService systemParams;
    private final StringRedisTemplate redis;
    private final SecurityEventRecorder recorder;
    private final Clock clock;

    public CostBreakerServiceImpl(LlmCallLogMapper callLogMapper,
                                  SystemParamService systemParams,
                                  StringRedisTemplate redis,
                                  SecurityEventRecorder recorder,
                                  Clock clock) {
        this.callLogMapper = callLogMapper;
        this.systemParams = systemParams;
        this.redis = redis;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Override
    public boolean isTripped(String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(redisKey(LocalDate.now(clock), scope)));
        } catch (Exception e) {
            log.warn("成本熔断状态读取失败按未熔断处理（不因 Redis 抖动阻断业务） scope={}", scope, e);
            return false;
        }
    }

    @Override
    public void onHeavyTaskAttempt(String platform, String botId, String groupId, String userId) {
        if (isTripped(SCOPE_GLOBAL)) {
            throw new CommandRejectedException(
                    "全局成本熔断已触发（" + ErrorCodes.SECURITY_BREAKER_TRIPPED + "），今日暂不接受新重任务");
        }
        if (platform != null && botId != null && groupId != null) {
            String scope = CostBreakerService.groupScope(platform, botId, groupId);
            if (isTripped(scope)) {
                throw new CommandRejectedException(
                        "本群成本熔断已触发（" + ErrorCodes.SECURITY_BREAKER_TRIPPED + "），今日暂不接受新重任务");
            }
        }
    }

    @Override
    public void onAbilityCall(String slotCode) {
        onAbilityCall(slotCode, null, null, null);
    }

    @Override
    public void onAbilityCall(String slotCode, String platform, String botId, String groupId) {
        if (slotCode == null || !GATED_ABILITY_SLOTS.contains(slotCode)) {
            return;   // chat_main 等基础对话槽位不查——保留被动回应
        }
        if (isTripped(SCOPE_GLOBAL)) {
            throw new CommandRejectedException(
                    "全局成本熔断中（" + ErrorCodes.SECURITY_BREAKER_TRIPPED + "），已暂停 " + slotCode + " 能力调用");
        }
        if (platform != null && botId != null && groupId != null
                && isTripped(CostBreakerService.groupScope(platform, botId, groupId))) {
            throw new CommandRejectedException(
                    "本群成本熔断中（" + ErrorCodes.SECURITY_BREAKER_TRIPPED + "），已暂停 " + slotCode + " 能力调用");
        }
    }

    @Override
    public void manualRelease(String operator) {
        manualRelease(SCOPE_GLOBAL, operator);
    }

    @Override
    public void manualRelease(String scope, String operator) {
        String effectiveScope = (scope == null || scope.isBlank()) ? SCOPE_GLOBAL : scope;
        LocalDate today = LocalDate.now(clock);
        String key = redisKey(today, effectiveScope);
        Boolean deleted = redis.delete(key);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "manualRelease");
        detail.put("scope", effectiveScope);
        detail.put("date", today.toString());
        detail.put("operator", operator != null ? operator : "admin");
        detail.put("wasTripped", Boolean.TRUE.equals(deleted));
        recorder.record(SecurityEventKind.CIRCUIT_BREAK, dimsOf(effectiveScope), detail, null);
        recorder.record(SecurityEventKind.ADMIN_OPERATE, dimsOf(effectiveScope), detail, null);
        log.info("成本熔断已手动解除 scope={} operator={} wasTripped={}", effectiveScope, operator, deleted);
    }

    /**
     * 分钟级定时聚合：默认 60 秒扫一次当日 is_replay=0 用量并与阈值比对
     */
    @Override
    @Scheduled(fixedDelay = 60_000)
    public BreakerSnapshot aggregateDaily() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();

        BigDecimal globalLimit = readLimit(PARAM_GLOBAL_DAILY_LIMIT, DEFAULT_GLOBAL_LIMIT);
        BigDecimal groupLimit = readLimit(PARAM_GROUP_DAILY_LIMIT, DEFAULT_GROUP_LIMIT);

        LlmCallLogAggregation globalAgg = callLogMapper.selectTokenTotals(startOfDay, endOfDay, null);
        BigDecimal globalUsage = globalAgg != null && globalAgg.getCostAmount() != null
                ? globalAgg.getCostAmount() : BigDecimal.ZERO;

        // 严格防抖动：当日一旦熔断，不再因后续聚合值 <= 阈值而自动清除
        boolean globalTripped = isTripped(SCOPE_GLOBAL);
        if (!globalTripped && globalLimit.compareTo(BigDecimal.ZERO) > 0
                && globalUsage.compareTo(globalLimit) >= 0) {
            trip(today, SCOPE_GLOBAL, globalUsage, globalLimit);
            globalTripped = true;
        }

        Map<String, BigDecimal> groupUsages = new LinkedHashMap<>();
        Map<String, Boolean> trippedGroups = new LinkedHashMap<>();
        List<LlmCallLogAggregation> perGroup = callLogMapper.selectCostAggregation(
                "GROUP", startOfDay, endOfDay, null);
        if (perGroup != null) {
            for (LlmCallLogAggregation row : perGroup) {
                String scope = row.getDimensionKey();
                if (scope == null || scope.isBlank()) {
                    continue;
                }
                BigDecimal usage = row.getCostAmount() != null ? row.getCostAmount() : BigDecimal.ZERO;
                groupUsages.put(scope, usage);
                boolean tripped = isTripped(scope);
                if (!tripped && groupLimit.compareTo(BigDecimal.ZERO) > 0
                        && usage.compareTo(groupLimit) >= 0) {
                    trip(today, scope, usage, groupLimit);
                    tripped = true;
                }
                if (tripped) {
                    trippedGroups.put(scope, true);
                }
            }
        }

        return new BreakerSnapshot(today.toString(), globalUsage, globalLimit, globalTripped,
                groupUsages, groupLimit, trippedGroups);
    }

    private void trip(LocalDate today, String scope, BigDecimal usage, BigDecimal limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime midnight = today.plusDays(1).atStartOfDay();
        Duration ttl = Duration.between(now, midnight);
        if (ttl.isZero() || ttl.isNegative()) {
            ttl = Duration.ofSeconds(1);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "tripped");
        payload.put("scope", scope);
        payload.put("date", today.toString());
        payload.put("trippedAt", now.toString());
        payload.put("usage", usage);
        payload.put("limit", limit);
        String json = JsonMappers.valueToStringOrNull(payload);
        redis.opsForValue().set(redisKey(today, scope), json != null ? json : "{}", ttl);
        recorder.record(SecurityEventKind.CIRCUIT_BREAK, dimsOf(scope), payload, null);
        log.warn("成本熔断已触发 scope={} usage={} limit={}", scope, usage, limit);
    }

    private BigDecimal readLimit(String key, BigDecimal defaultValue) {
        String raw = systemParams.getString(key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("熔断阈值配置非法 key={} raw={}，回退缺省 {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    static String redisKey(LocalDate date, String scope) {
        return "breaker:" + date + ":" + scope;
    }

    private static SecurityDims dimsOf(String scope) {
        if (scope == null || SCOPE_GLOBAL.equals(scope)) {
            return SecurityDims.none();
        }
        String[] parts = scope.split(":", 3);
        return parts.length == 3
                ? SecurityDims.ofGroup(parts[0], parts[1], parts[2])
                : SecurityDims.none();
    }
}
