package cn.edu.scut.chatbot.observability.bridge;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 导出管道健康指标（T017、FR-026 可丢不可卡）：事件桥内存计数器（AtomicLong 快照），
 * 喂给「导出管道异常」告警探针与 quickstart 排障。
 *
 * <p>两个信号源：
 * <ul>
 *   <li>事件丢弃数——引擎事件桥外壳队列满丢弃（观测事件允许丢，持续增长说明消费端缺位）；</li>
 *   <li>导出失败数与最近失败时刻——OTLP 导出器包装层计数（Collector 不可达的重试信号；
 *       BatchSpanProcessor 自带退避重试与队列上限，这里只做可见性不参与控制流）。</li>
 * </ul>
 */
@Component
public class ExportHealthMetrics {

    private final AtomicLong eventDropCount = new AtomicLong();

    private final AtomicLong exportFailureCount = new AtomicLong();

    /**
     * 最近一次导出失败的时刻（epoch millis；-1 = 从未失败——快照口径用）
     */
    private final AtomicLong lastExportFailureAtMs = new AtomicLong(-1);

    public void recordEventDrop(int count) {
        eventDropCount.addAndGet(count);
    }

    public void recordExportFailure() {
        exportFailureCount.incrementAndGet();
        lastExportFailureAtMs.set(System.currentTimeMillis());
    }

    public long eventDropCount() {
        return eventDropCount.get();
    }

    public long exportFailureCount() {
        return exportFailureCount.get();
    }

    public long lastExportFailureAtMs() {
        return lastExportFailureAtMs.get();
    }
}
