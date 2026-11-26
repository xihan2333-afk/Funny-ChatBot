package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.llm.slot.CredentialMapper;
import cn.edu.scut.chatbot.llm.slot.OwnerAvailableCount;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 凭证全冷却探针（credential.exhausted）：某供应商可用 key 数 = 0 即命中
 * （critical——所有 key 都在冷却或停用，该供应商调用全挂）。取可用数最少的供应商
 * 作为维度，同规则对不同供应商各自抑制。
 */
@Component
public class CredentialExhaustedProbe implements MetricProbe {

    private final CredentialMapper credentialMapper;

    public CredentialExhaustedProbe(CredentialMapper credentialMapper) {
        this.credentialMapper = credentialMapper;
    }

    @Override
    public String metric() {
        return "credential.exhausted";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        List<OwnerAvailableCount> rows = credentialMapper.countAvailableByProvider();
        OwnerAvailableCount worst = rows.stream()
                .reduce((a, b) -> a.getAvailableCount() <= b.getAvailableCount() ? a : b)
                .orElse(null);
        if (worst == null) {
            return Optional.empty();
        }
        return Optional.of(new AlertSample(worst.getAvailableCount(),
                "供应商 " + worst.getOwnerCode() + " 可用 key 数 " + worst.getAvailableCount(),
                worst.getOwnerCode(), null));
    }
}
