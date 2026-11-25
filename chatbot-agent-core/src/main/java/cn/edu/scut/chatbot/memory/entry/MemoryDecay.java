package cn.edu.scut.chatbot.memory.entry;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * 记忆强度衰减纯函数（contracts/sediment-pipeline.md §5、research R11）：
 * 读侧（检索重排的衰减因子）与写侧（每日批任务的归档判定）共用同一实现——
 * 单一实现是唯一防读写口径漂移的形态。
 *
 * <p>双时间轴语义：
 * <ul>
 *   <li><b>发生轴</b>：occurredAt 越久远，衰减速率越快——每满
 *       {@link #AGE_ACCELERATE_DAYS} 天速率翻倍（上周的事慢慢忘，去年的事很快忘）；</li>
 *   <li><b>检索轴</b>：衰减时钟自「最近一次被检索」起算（无检索记录则自发生起算）——
 *       常被检索的条目衰减被抵消（检索热度抵消衰减）。</li>
 * </ul>
 * 公式：{@code effective = clamp(strength − rate × staleDays, 0, 100)}，
 * 其中 {@code rate = dailyDecayRate × 2^floor(ageDays / AGE_ACCELERATE_DAYS)}
 * （阶梯翻倍——每满 30 天速率 ×2，不满 30 天按原速率）、
 * {@code staleDays = lastAccessedAt 为空时取 ageDays}。
 * 纯函数、无 IO——FixedClock 拨动可精确断言。
 */
public final class MemoryDecay {

    private static final Logger log = LoggerFactory.getLogger(MemoryDecay.class);

    public static final String PARAM_KEY = "memory.decay.strength";

    private MemoryDecay() {
    }

    /**
     * 发生轴加速周期（天）：每满 30 天衰减速率翻一倍
     */
    public static final int AGE_ACCELERATE_DAYS = 30;

    private static final double DAY_MILLIS = 24.0 * 3600 * 1000;

    /**
     * 衰减参数（system_param memory.decay.strength 热变更；archive 相关字段属批任务用）
     */
    public record DecayParams(int archiveThreshold, int archiveKeepDays,
                              int groupSoftLimit, double dailyDecayRate) {

        public static DecayParams defaults() {
            return new DecayParams(15, 180, 5000, 1.0);
        }
    }

    /**
     * 从运行层装载衰减参数（缺行/坏 JSON 走全缺省——参数错误不拖垮业务）。
     * 读侧（检索重排）与写侧（每日归档批任务）共用同一装载，防口径漂移
     */
    public static DecayParams load(SystemParamService systemParams) {
        DecayParams fallback = DecayParams.defaults();
        String raw = systemParams == null
                ? null : systemParams.getString(PARAM_KEY, null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = JsonMappers.shared().readTree(raw);
            return new DecayParams(
                    node.path("archiveThreshold").asInt(fallback.archiveThreshold()),
                    node.path("archiveKeepDays").asInt(fallback.archiveKeepDays()),
                    node.path("groupSoftLimit").asInt(fallback.groupSoftLimit()),
                    node.path("dailyDecayRate").asDouble(fallback.dailyDecayRate()));
        } catch (Exception e) {
            log.warn("memory.decay.strength 解析失败走缺省：{}", e.getMessage());
            return fallback;
        }
    }

    /**
     * 此刻应有的有效强度（0~100）：重排打分与归档判定共用
     */
    public static double effectiveStrength(int strength, Instant occurredAt,
                                           Instant lastAccessedAt, Instant now,
                                           DecayParams params) {
        double ageDays = elapsedDays(occurredAt, now);
        double staleDays = lastAccessedAt == null
                ? ageDays : elapsedDays(lastAccessedAt, now);
        double rate = params.dailyDecayRate()
                * Math.pow(2, Math.floor(ageDays / AGE_ACCELERATE_DAYS));
        double effective = strength - rate * staleDays;
        return Math.max(0, Math.min(100, effective));
    }

    /**
     * 重排用的衰减因子形态（0~1：effective / strength——强度 0 的条目因子为 0）
     */
    public static double decayFactor(int strength, Instant occurredAt,
                                     Instant lastAccessedAt, Instant now,
                                     DecayParams params) {
        if (strength <= 0) {
            return 0;
        }
        return effectiveStrength(strength, occurredAt, lastAccessedAt, now, params) / strength;
    }

    private static double elapsedDays(Instant from, Instant now) {
        long millis = now.toEpochMilli() - from.toEpochMilli();
        return Math.max(0, millis / DAY_MILLIS);
    }
}
