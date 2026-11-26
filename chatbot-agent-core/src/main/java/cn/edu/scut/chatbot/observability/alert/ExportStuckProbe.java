package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.observability.bridge.ExportHealthMetrics;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 导出管道异常探针（observe.exportStuck）：最近一次导出失败时间落在规则窗口内
 * 即命中（warn——Collector 不可达或持续失败；丢弃计数是累计值无窗口语义，
 * 以导出失败时间为准，FR-026 可丢不可卡的值班员）。
 */
@Component
public class ExportStuckProbe implements MetricProbe {

    private final ExportHealthMetrics healthMetrics;

    public ExportStuckProbe(ExportHealthMetrics healthMetrics) {
        this.healthMetrics = healthMetrics;
    }

    @Override
    public String metric() {
        return "observe.exportStuck";
    }

    @Override
    public Optional<AlertSample> sample(AlertRuleDO rule, Instant now) {
        long lastFailureAt = healthMetrics.lastExportFailureAtMs();
        if (lastFailureAt <= 0) {
            return Optional.empty();
        }
        long windowMillis = Math.max(rule.getWindowMin(), 1) * 60_000L;
        boolean withinWindow = now.toEpochMilli() - lastFailureAt <= windowMillis;
        if (!withinWindow) {
            return Optional.empty();
        }
        return Optional.of(new AlertSample(1,
                "观测导出管道异常：最近失败于 " + Instant.ofEpochMilli(lastFailureAt)
                        + "，累计失败 " + healthMetrics.exportFailureCount() + " 次",
                null, null));
    }
}
