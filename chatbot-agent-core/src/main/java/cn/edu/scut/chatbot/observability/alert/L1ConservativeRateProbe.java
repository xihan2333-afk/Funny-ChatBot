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
 * L1 保守降级率探针（l1.conservativeRate）：窗口内 L1 失败保守 ignore
 * （decision_layer=degrade）占 L1 判定总数的比例，默认阈值 20% 命中（warn——
 * 小模型评估器频繁不可用或判断退化，主动社交在缩水）。
 */
@Component
public class L1ConservativeRateProbe implements MetricProbe {

    private final TriggerEventMapper triggerEventMapper;
    private final Clock clock;

    public L1ConservativeRateProbe(TriggerEventMapper triggerEventMapper, Clock clock) {
        this.triggerEventMapper = triggerEventMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "l1.conservativeRate";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime from = LocalDateTime.ofInstant(
                now.minusSeconds(Math.max(rule.getWindowMin(), 1) * 60L), clock.getZone());
        List<DecisionLayerCount> counts = triggerEventMapper.countDecisionByWindow(
                from, LocalDateTime.ofInstant(now, clock.getZone()), null);

        long l1 = 0;
        long degrade = 0;
        for (DecisionLayerCount cnt : counts) {
            if ("L1".equals(cnt.getDecisionLayer())) {
                l1 += cnt.getCount();
            } else if ("degrade".equals(cnt.getDecisionLayer())) {
                degrade += cnt.getCount();
            }
        }
        long judged = l1 + degrade;
        if (judged == 0) {
            return Optional.empty();
        }
        double rate = (double) degrade / judged;
        return Optional.of(new AlertSample(rate,
                String.format("近 %d 分钟 L1 保守降级率 %.1f%%（%d/%d）",
                        rule.getWindowMin(), rate * 100, degrade, judged),
                null, null));
    }
}
