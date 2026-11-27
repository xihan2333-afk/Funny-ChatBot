package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.security.audit.SecurityEventMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 拦截率突增探针（security.eventBurst）：窗口内安全事件数对比 7 日基线的
 * 小时均值倍数（默认 3 倍命中，warn——攻击或滥用正在发生的信号）。
 * 基线总量为 0 时不判定（无历史可比）。
 */
@Component
public class SecurityEventBurstProbe implements MetricProbe {

    private static final long BASELINE_DAYS = 7;

    private final SecurityEventMapper securityEventMapper;
    private final Clock clock;

    public SecurityEventBurstProbe(SecurityEventMapper securityEventMapper, Clock clock) {
        this.securityEventMapper = securityEventMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "security.eventBurst";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        long windowMinutes = Math.max(rule.getWindowMin(), 1);
        LocalDateTime windowStart = LocalDateTime.ofInstant(
                now.minusSeconds(windowMinutes * 60L), clock.getZone());
        LocalDateTime baselineStart = LocalDateTime.ofInstant(
                now.minusSeconds(BASELINE_DAYS * 24 * 3600L), clock.getZone());
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, clock.getZone());

        long windowCount = securityEventMapper.countAllBetween(windowStart, nowLdt);
        long baselineTotal = securityEventMapper.countAllBetween(baselineStart, windowStart);
        double baselineHours = BASELINE_DAYS * 24.0;
        double baselineHourlyAvg = baselineTotal / baselineHours;
        if (baselineTotal == 0) {
            return Optional.empty();
        }
        double ratio = windowCount / (baselineHourlyAvg * (windowMinutes / 60.0));
        return Optional.of(new AlertSample(ratio,
                String.format("近 %d 分钟安全事件 %d 次，7 日基线小时均值 %.2f（倍数 %.1f）",
                        windowMinutes, windowCount, baselineHourlyAvg, ratio),
                null, null));
    }
}
