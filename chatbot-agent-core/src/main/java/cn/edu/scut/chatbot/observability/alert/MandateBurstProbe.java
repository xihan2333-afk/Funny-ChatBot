package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.security.audit.SecurityEventMapper;
import cn.edu.scut.chatbot.security.audit.UserDeniedCount;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 越权尝试集中探针（security.mandateBurst）：窗口内同用户 mandateDenied 计数
 * 最高的一位（默认 >= 10 次/小时命中，warn——提示词注入试图借 Agent 之手越权的
 * 典型模式；userId 是抑制维度）。
 */
@Component
public class MandateBurstProbe implements MetricProbe {

    private final SecurityEventMapper securityEventMapper;
    private final Clock clock;

    public MandateBurstProbe(SecurityEventMapper securityEventMapper, Clock clock) {
        this.securityEventMapper = securityEventMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "security.mandateBurst";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime since = LocalDateTime.ofInstant(
                now.minusSeconds(Math.max(rule.getWindowMin(), 1) * 60L), clock.getZone());
        UserDeniedCount top = securityEventMapper.selectTopMandateUser(since);
        if (top == null || top.getCount() == 0) {
            return Optional.empty();
        }
        return Optional.of(new AlertSample(top.getCount(),
                String.format("用户 %s 窗口内 Mandate 越权被拒 %d 次", top.getUserId(), top.getCount()),
                top.getUserId(), null));
    }
}
