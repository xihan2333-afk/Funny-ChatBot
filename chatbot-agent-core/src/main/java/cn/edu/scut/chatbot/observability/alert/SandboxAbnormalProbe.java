package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 沙箱异常探针（security.sandboxAbnormal）：窗口内 sandboxAbnormal 事件计数
 * （输出特征命中或异常模式），> 0 即命中（critical——沙箱逃逸审计的值班员，
 * 2.5.4 明文：沙箱类安全告警归严重级）。
 */
@Component
public class SandboxAbnormalProbe implements MetricProbe {

    private final SecurityEventMapper securityEventMapper;
    private final Clock clock;

    public SandboxAbnormalProbe(SecurityEventMapper securityEventMapper, Clock clock) {
        this.securityEventMapper = securityEventMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "security.sandboxAbnormal";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime since = LocalDateTime.ofInstant(
                now.minusSeconds(Math.max(rule.getWindowMin(), 1) * 60L), clock.getZone());
        long count = securityEventMapper.countByKindSince(
                SecurityEventKind.SANDBOX_ABNORMAL.code(), since);
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(new AlertSample(count,
                "窗口内沙箱异常事件 " + count + " 次（输出特征命中或异常模式）", null, null));
    }
}
