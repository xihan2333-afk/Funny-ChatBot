package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 凭证失效探针（credential.authFail）：窗口内 credentialInvalid 事件计数
 * （auth 类错误，2.3.6 既定信号纳入统一视图），> 0 即命中（critical——key 失效
 * 会被停用，可用池在缩水，与凭证全冷却告警形成先行/后续）。
 */
@Component
public class CredentialAuthFailProbe implements MetricProbe {

    private final SecurityEventMapper securityEventMapper;
    private final Clock clock;

    public CredentialAuthFailProbe(SecurityEventMapper securityEventMapper, Clock clock) {
        this.securityEventMapper = securityEventMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "credential.authFail";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime since = LocalDateTime.ofInstant(
                now.minusSeconds(Math.max(rule.getWindowMin(), 1) * 60L), clock.getZone());
        long count = securityEventMapper.countByKindSince(
                SecurityEventKind.CREDENTIAL_INVALID.code(), since);
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(new AlertSample(count,
                "窗口内凭证鉴权失败 " + count + " 次（key 可能被上游吊销）", null, null));
    }
}
