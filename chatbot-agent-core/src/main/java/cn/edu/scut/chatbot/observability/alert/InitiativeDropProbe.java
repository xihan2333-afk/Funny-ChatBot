package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.engine.intake.DecisionLayerCount;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 主动行为异常探针（social.initiativeDrop）：主动开口（internal/silenceBreak）的
 * accept 成功率，近窗口对比前 7 日基线骤降（比值 < 0.5 命中，info——社交主动性
 * 变化常是人格或评估器漂移的信号）。基线无样本时不判定。
 */
@Component
public class InitiativeDropProbe implements MetricProbe {

    private static final long BASELINE_DAYS = 7;

    private final TriggerEventMapper triggerEventMapper;
    private final Clock clock;

    public InitiativeDropProbe(TriggerEventMapper triggerEventMapper, Clock clock) {
        this.triggerEventMapper = triggerEventMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "social.initiativeDrop";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        long windowMillis = Math.max(rule.getWindowMin(), 1) * 60_000L;
        Instant windowStart = now.minusMillis(windowMillis);
        Instant baselineStart = now.minusMillis(BASELINE_DAYS * 24 * 3600_000L);

        Double recentRate = silenceBreakRate(windowStart, now);
        Double baselineRate = silenceBreakRate(baselineStart, windowStart);
        if (recentRate == null || baselineRate == null || baselineRate == 0) {
            return Optional.empty();
        }
        double ratio = recentRate / baselineRate;
        return Optional.of(new AlertSample(ratio,
                String.format("主动开口成功率 %.1f%%，7 日基线 %.1f%%（比值 %.2f）",
                        recentRate * 100, baselineRate * 100, ratio),
                null, null));
    }

    /**
     * [from, to) 区间 silenceBreak 的 accept 占比；无样本返回 null
     */
    private Double silenceBreakRate(Instant from, Instant to) {
        List<DecisionLayerCount> counts = triggerEventMapper.countInternalDecision(
                "silenceBreak",
                LocalDateTime.ofInstant(from, clock.getZone()),
                LocalDateTime.ofInstant(to, clock.getZone()),
                null);
        long total = counts.stream().mapToLong(DecisionLayerCount::getCount).sum();
        if (total == 0) {
            return null;
        }
        long accept = counts.stream()
                .filter(c -> "accept".equals(c.getDecision()))
                .mapToLong(DecisionLayerCount::getCount).sum();
        return (double) accept / total;
    }
}
