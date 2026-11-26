package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.llm.slot.LlmCallLogAggregation;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 日成本超限探针（cost.dailyOver）：当日 0 点起 is_replay=0 的成本合计，与
 * system_param security.breaker.globalDailyLimit（threshold 的 paramKey 指向）比较。
 * 取值口径与成本熔断同源（llm_call_log 事实源）。
 */
@Component
public class CostDailyOverProbe implements MetricProbe {

    private final LlmCallLogMapper llmCallLogMapper;
    private final Clock clock;

    public CostDailyOverProbe(LlmCallLogMapper llmCallLogMapper, Clock clock) {
        this.llmCallLogMapper = llmCallLogMapper;
        this.clock = clock;
    }

    @Override
    public String metric() {
        return "cost.dailyOver";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        LocalDateTime todayStart = LocalDate.ofInstant(now, clock.getZone()).atStartOfDay();
        LlmCallLogAggregation totals = llmCallLogMapper.selectTokenTotals(todayStart, null, null);
        BigDecimal cost = totals == null ? BigDecimal.ZERO
                : totals.getCostAmount() == null ? BigDecimal.ZERO : totals.getCostAmount();
        return Optional.of(new AlertSample(cost.doubleValue(),
                String.format("当日成本 %.4f（is_replay=0 口径）", cost.doubleValue()),
                null, null));
    }
}
