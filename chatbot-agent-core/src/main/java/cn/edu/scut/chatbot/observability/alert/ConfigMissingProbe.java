package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 槽位缺失探针（config.missing）：窗口内 error_code 以 llm.config_missing 开头的
 * 失败 Turn 计数，> 0 即命中（critical——槽位配置坏了，系统在空转）。
 */
@Component
public class ConfigMissingProbe implements MetricProbe {

    private final TurnMapper turnMapper;
    private final Clock clock;

    public ConfigMissingProbe(TurnMapper turnMapper, Clock clock) {
        this.turnMapper = turnMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "config.missing";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime from = LocalDateTime.ofInstant(
                now.minusSeconds(windowSeconds(rule)), clock.getZone());
        long count = turnMapper.countByErrorCodePrefix("llm.config_missing",
                from, LocalDateTime.ofInstant(now, clock.getZone()));
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(new AlertSample(count,
                "窗口内槽位缺失错误 " + count + " 次（检查槽位与模型绑定配置）", null, null));
    }

    private long windowSeconds(AlertRuleDO rule) {
        return Math.max(rule.getWindowMin(), 1) * 60L;
    }
}
