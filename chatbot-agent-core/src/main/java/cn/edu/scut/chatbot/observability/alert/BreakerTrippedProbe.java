package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 熔断触发探针（breaker.tripped）：窗口内 circuitBreak 事件计数，> 0 即命中
 * （critical——成本闸已落下，当日不再自动解除；通知值班员确认预算与用量）。
 */
@Component
public class BreakerTrippedProbe implements MetricProbe {

    private final SecurityEventMapper securityEventMapper;
    private final Clock clock;

    public BreakerTrippedProbe(SecurityEventMapper securityEventMapper, Clock clock) {
        this.securityEventMapper = securityEventMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "breaker.tripped";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime since = LocalDateTime.ofInstant(
                now.minusSeconds(Math.max(rule.getWindowMin(), 1) * 60L), clock.getZone());
        long count = securityEventMapper.countByKindSince(
                SecurityEventKind.CIRCUIT_BREAK.code(), since);
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(new AlertSample(count,
                "窗口内成本熔断触发 " + count + " 次（检查 security.breaker.* 阈值与用量）",
                null, null));
    }
}
