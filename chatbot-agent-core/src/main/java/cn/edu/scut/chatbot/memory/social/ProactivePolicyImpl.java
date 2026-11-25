package cn.edu.scut.chatbot.memory.social;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.memory.emotion.EmotionService;
import cn.edu.scut.chatbot.memory.profile.GroupProfileDO;
import cn.edu.scut.chatbot.memory.profile.GroupProfileMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 主动开口策略实现。三参数（memory.social.proactive_params）system_param 热变更；
 * 活跃时段取群体画像（group_profile.active_hours——显著活跃小时 = 计数不低于全时段均值，
 * 偶发一条的凌晨时段不构成活跃时段；无画像回保守默认 8~22 点）；情绪微调——
 * 强负面（负面标签且有效强度 ≥70）当日预算减半 + 破冰阈值提高 1.5 倍
 * （闹脾气不想主动搭话也是玩点）。
 */
@Component
public class ProactivePolicyImpl implements ProactivePolicy {

    private static final Logger log = LoggerFactory.getLogger(ProactivePolicyImpl.class);

    static final String PARAMS_KEY = "memory.social.proactive_params";

    /**
     * 强负面判定的负面标签集（默认八标签的负面子集；人格自定义负面词汇的语义判定归后续演进）
     */
    static final Set<String> NEGATIVE_LABELS = Set.of("不爽", "委屈");

    /**
     * 强负面强度门槛（含微调幅度：阈值 ×1.5 向上取整）
     */
    static final int STRONG_NEGATIVE_INTENSITY = 70;

    private static final double NEGATIVE_THRESHOLD_FACTOR = 1.5;

    private final SystemParamService systemParams;
    private final GroupProfileMapper groupProfileMapper;
    private final EmotionService emotionService;

    public ProactivePolicyImpl(SystemParamService systemParams,
                               GroupProfileMapper groupProfileMapper,
                               EmotionService emotionService) {
        this.systemParams = systemParams;
        this.groupProfileMapper = groupProfileMapper;
        this.emotionService = emotionService;
    }

    @Override
    public ProactiveParams effectiveParams(GroupKey group) {
        int silenceBreakMinutes = 30;
        int minIntervalHours = 2;
        int dailyBudget = 10;
        try {
            String raw = systemParams.getString(PARAMS_KEY, null);
            if (raw != null && !raw.isBlank()) {
                JsonNode node = JsonMappers.shared().readTree(raw);
                silenceBreakMinutes = Math.max(1, node.path("silenceBreakMinutes").asInt(silenceBreakMinutes));
                minIntervalHours = Math.max(1, node.path("minIntervalHours").asInt(minIntervalHours));
                dailyBudget = Math.max(0, node.path("dailyBudget").asInt(dailyBudget));
            }
        } catch (Exception e) {
            log.warn("memory.social.proactive_params 解析失败走缺省：{}", e.getMessage());
        }

        Set<Integer> activeHours = resolveActiveHours(group);

        // 情绪微调：强负面 → 预算减半 + 破冰阈值提高（读取失败不拦策略）
        try {
            var emotion = emotionService.read(group);
            if (emotion != null && NEGATIVE_LABELS.contains(emotion.label())
                    && emotion.intensity() >= STRONG_NEGATIVE_INTENSITY) {
                dailyBudget = Math.max(1, dailyBudget / 2);
                silenceBreakMinutes = (int) Math.ceil(silenceBreakMinutes * NEGATIVE_THRESHOLD_FACTOR);
            }
        } catch (Exception e) {
            log.warn("情绪读取失败跳过微调 group={}：{}", group.groupId(), e.getMessage());
        }

        return new ProactiveParams(silenceBreakMinutes, minIntervalHours, dailyBudget, activeHours);
    }

    /**
     * 活跃时段解析：group_profile.active_hours（{"14":12,...} 按小时计数）→
     * 计数不低于均值的小时集合；无画像或解析失败回默认 8~22 点
     */
    private Set<Integer> resolveActiveHours(GroupKey group) {
        try {
            GroupProfileDO profile = groupProfileMapper.selectByGroup(
                    group.platform(), group.botId(), group.groupId());
            if (profile == null || profile.getActiveHours() == null || profile.getActiveHours().isBlank()) {
                return ProactiveParams.defaultActiveHours();
            }
            JsonNode node = JsonMappers.shared().readTree(profile.getActiveHours());
            Map<Integer, Long> hourly = new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> {
                try {
                    hourly.put(Integer.parseInt(entry.getKey()), entry.getValue().asLong(0));
                } catch (NumberFormatException ignored) {
                    // 非数字小时键跳过
                }
            });
            if (hourly.isEmpty()) {
                return ProactiveParams.defaultActiveHours();
            }
            long total = hourly.values().stream().mapToLong(Long::longValue).sum();
            double average = total / (double) hourly.size();
            Set<Integer> active = new TreeSet<>();
            hourly.forEach((hour, count) -> {
                if (count >= average) {
                    active.add(hour);
                }
            });
            return active.isEmpty() ? ProactiveParams.defaultActiveHours() : active;
        } catch (Exception e) {
            log.warn("活跃时段解析失败走默认 group={}：{}", group.groupId(), e.getMessage());
            return ProactiveParams.defaultActiveHours();
        }
    }
}
