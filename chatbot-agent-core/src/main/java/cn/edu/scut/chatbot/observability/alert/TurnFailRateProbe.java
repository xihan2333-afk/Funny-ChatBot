package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.engine.turn.TurnStatusCount;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Turn 失败率探针（turn.failRate）：窗口内 FAILED 占比（分母为全部 Turn 状态计数），
 * 默认阈值 10% 命中（warn——生成质量或上游异常的先兆）。
 */
@Component
public class TurnFailRateProbe implements MetricProbe {

    private final TurnMapper turnMapper;
    private final Clock clock;

    public TurnFailRateProbe(TurnMapper turnMapper, Clock clock) {
        this.turnMapper = turnMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "turn.failRate";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime from = LocalDateTime.ofInstant(
                now.minusSeconds(Math.max(rule.getWindowMin(), 1) * 60L), clock.getZone());
        List<TurnStatusCount> counts = turnMapper.countByStatus(from,
                LocalDateTime.ofInstant(now, clock.getZone()), null);

        long total = counts.stream().mapToLong(TurnStatusCount::getCount).sum();
        if (total == 0) {
            return Optional.empty();
        }
        long failed = counts.stream()
                .filter(c -> "FAILED".equals(c.getStatus()))
                .mapToLong(TurnStatusCount::getCount).sum();
        double rate = (double) failed / total;
        return Optional.of(new AlertSample(rate,
                String.format("近 %d 分钟 Turn 失败率 %.1f%%（%d/%d）",
                        rule.getWindowMin(), rate * 100, failed, total),
                null, null));
    }
}
