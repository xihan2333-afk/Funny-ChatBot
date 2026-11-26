package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 告警引擎（observe-query 契约第 2 节）：周期逐条执行启用的 alert_rule——
 * metric 路由探针取样 → threshold 判定 → 窗口内抑制 → 命中落 alert_notification。
 *
 * <p>周期：调度 tick 30 秒一次，实际执行间隔由 system_param
 * observe.alert.scanIntervalMin 控制（默认 1 分钟）——@Scheduled 注解无法读数据面
 * 参数，tick 是参数化周期的载体。
 *
 * <p>抑制（同规则同维度窗口期内不重复）：维度标记以「 |dim=xxx」后缀写进通知
 * summary（alert_notification 表无独立维度列——data-model 2.3 定义，历史通知的
 * 语义在 summary 里自洽），抑制判定查窗口内该规则通知按后缀匹配。无维度探针
 * （dimensionKey=null）即同规则窗口内不重复。
 *
 * <p>纪律：单条规则或单个探针异常不外溢——log 后继续下一条（告警面故障
 * 不允许拖垮任何东西，包括告警面自己）。
 */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    /**
     * 调度 tick 周期（毫秒）——实际执行间隔由 observe.alert.scanIntervalMin 决定
     */
    private static final long TICK_MILLIS = 30_000;

    /**
     * 默认抑制窗口（分钟）：threshold JSON 未写 suppressMin 时兜底
     */
    private static final int DEFAULT_SUPPRESS_MIN = 30;

    private final AlertRuleMapper ruleMapper;
    private final AlertNotificationMapper notificationMapper;
    private final SystemParamService params;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, MetricProbe> probesByMetric;

    private volatile Instant lastRunAt = Instant.EPOCH;

    public AlertEngine(AlertRuleMapper ruleMapper,
                       AlertNotificationMapper notificationMapper,
                       SystemParamService params,
                       ObjectMapper objectMapper,
                       Clock clock,
                       List<MetricProbe> probes) {
        this.ruleMapper = ruleMapper;
        this.notificationMapper = notificationMapper;
        this.params = params;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.probesByMetric = probes.stream()
                .collect(Collectors.toMap(MetricProbe::metric, Function.identity()));
    }

    @Scheduled(fixedDelay = TICK_MILLIS, initialDelay = TICK_MILLIS)
    public void tick() {
        int intervalMin = Math.max(params.getInt("observe.alert.scanIntervalMin", 1), 1);
        Instant now = clock.instant();
        if (!lastRunAt.equals(Instant.EPOCH) && now.isBefore(lastRunAt.plus(intervalMin, ChronoUnit.MINUTES))) {
            return;
        }
        lastRunAt = now;
        try {
            evaluateOnce();
        } catch (Exception e) {
            log.error("告警引擎本轮执行异常（下轮继续）", e);
        }
    }

    /**
     * 单轮判定（public 供测试与管理面手动触发）：逐条执行启用规则。
     */
    public void evaluateOnce() {
        for (AlertRuleDO rule : ruleMapper.selectEnabled()) {
            try {
                evaluateRule(rule);
            } catch (Exception e) {
                log.error("告警规则执行异常（跳过该条） ruleId={} metric={}",
                        rule.getId(), rule.getMetric(), e);
            }
        }
    }

    void evaluateRule(AlertRuleDO rule) {
        MetricProbe probe = probesByMetric.get(rule.getMetric());
        if (probe == null) {
            log.warn("告警规则无对应探针（检查 metric 拼写或探针实现） ruleId={} metric={}",
                    rule.getId(), rule.getMetric());
            return;
        }
        AlertThresholdSpec spec = AlertThresholdSpec.parse(objectMapper, rule.getThreshold());
        Instant now = clock.instant();
        probe.sample(rule, now).ifPresent(sample -> {
            if (!matchesThreshold(spec, sample)) {
                return;
            }
            if (suppressed(rule, spec, sample, now)) {
                return;
            }
            fire(rule, sample);
        });
    }

    private boolean matchesThreshold(AlertThresholdSpec spec, AlertSample sample) {
        double bound = spec.value() != null ? spec.value()
                : spec.paramKey() != null ? params.getLong(spec.paramKey(), 0) : 0;
        AlertThresholdSpec literal = new AlertThresholdSpec(spec.op(), bound, null, spec.suppressMin());
        return literal.matches(sample.value());
    }

    /**
     * 窗口内抑制：该规则在 suppressMin 分钟内已有同维度通知（维度后缀匹配）即抑制
     */
    private boolean suppressed(AlertRuleDO rule, AlertThresholdSpec spec, AlertSample sample, Instant now) {
        int suppressMin = spec.suppressMin() != null ? spec.suppressMin() : DEFAULT_SUPPRESS_MIN;
        LocalDateTime since = LocalDateTime.ofInstant(
                now.minus(suppressMin, ChronoUnit.MINUTES), clock.getZone());
        List<AlertNotificationDO> recent = notificationMapper.selectRecentByRule(rule.getId(), since);
        String dimMark = dimensionMark(sample.dimensionKey());
        return recent.stream().anyMatch(n -> n.getSummary() != null && n.getSummary().endsWith(dimMark));
    }

    private void fire(AlertRuleDO rule, AlertSample sample) {
        AlertNotificationDO notification = new AlertNotificationDO();
        notification.setRuleId(rule.getId());
        notification.setRuleName(rule.getName());
        notification.setLevel(rule.getLevel());
        String summary = sample.summary() == null ? "" : sample.summary();
        String withDim = summary + dimensionMark(sample.dimensionKey());
        notification.setSummary(withDim.length() > 512 ? withDim.substring(0, 512) : withDim);
        notification.setTraceId(sample.traceId());
        notification.setReadFlag(false);
        notificationMapper.insert(notification);
        log.info("告警命中落库 rule={} level={} value={} summary={}",
                rule.getName(), rule.getLevel(), sample.value(), summary);
    }

    private String dimensionMark(String dimensionKey) {
        return dimensionKey == null ? "" : " |dim=" + dimensionKey;
    }

    /**
     * 测试拨钟用：重置调度节流状态
     */
    void resetScheduleState() {
        lastRunAt = Instant.EPOCH;
    }
}
