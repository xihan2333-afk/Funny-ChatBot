package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨时刻闭合注册表（observability-bridge 契约第 4 节、research R4、T016）：
 * turn / task 长生命周期 Span 开在 DecisionMade(accept) / TaskTransitioned 创建时刻，
 * 结束事件在另一时刻另一线程到达——OTel Span 不能跨线程长期持有上下文，显式注册表
 * + 周期扫描超时兜底是标准解法（结束事件丢失时 Span 不至于永不闭合污染聚合口径）。
 *
 * <p>entityKey 约定：{@code turn:{turnId}}、{@code task:{taskId}}。Turn 默认 10 分钟、
 * Task 默认 48 小时（system_param observe 域可配，FixedClock 拨钟可测）。
 * 超时强制闭合打 chatbot.abnormal_end=true，计数暴露给告警探针。
 */
@Component
public class SpanClosureRegistry {

    private static final Logger log = LoggerFactory.getLogger(SpanClosureRegistry.class);

    /**
     * 属性名（abnormal_end 标记——超时兜底闭合的事件语义，与正常闭合区分）
     */
    public static final String ATTR_ABNORMAL_END = "chatbot.abnormal_end";

    private record PendingSpan(Span span, Instant deadline) {
    }

    private final Map<String, PendingSpan> pending = new ConcurrentHashMap<>();

    private final SystemParamService systemParamService;

    private final Clock clock;

    /**
     * 超时兜底闭合计数（告警探针读数）
     */
    private final AtomicLong abnormalEndCount = new AtomicLong();

    private volatile boolean closed = false;

    private Thread scanThread;

    public SpanClosureRegistry(SystemParamService systemParamService, Clock clock) {
        this.systemParamService = systemParamService;
        this.clock = clock;
    }

    @PostConstruct
    void startScanner() {
        long scanIntervalSec = systemParamService.getLong("observe.span.closure.scanIntervalSec", 30);
        scanThread = Thread.ofVirtual().name("span-closure-scan").start(() -> scanLoop(scanIntervalSec));
    }

    @PreDestroy
    void stopScanner() {
        closed = true;
        if (scanThread != null) {
            scanThread.interrupt();
        }
    }

    /**
     * 登记长生命周期 Span（开 Span 处调用；timeout 为该实体的兜底超时）
     */
    public void register(String entityKey, Span span, Duration timeout) {
        pending.put(entityKey, new PendingSpan(span, clock.instant().plus(timeout)));
    }

    /**
     * 结束事件到达：正常闭合（打 abnormal_end=false 显式留痕后 end）
     */
    public void close(String entityKey) {
        PendingSpan entry = pending.remove(entityKey);
        if (entry != null) {
            closeSpan(entry.span(), false);
        }
    }

    /**
     * 结束事件到达但需要补充属性再闭合（先补属性再 close 的组合入口）
     */
    public Span take(String entityKey) {
        PendingSpan entry = pending.remove(entityKey);
        return entry != null ? entry.span() : null;
    }

    /**
     * 周期扫描：超时项强制闭合并打 abnormal_end=true（FixedClock 拨钟可测）
     *
     * @return 本轮强制闭合数
     */
    public long forceCloseExpired(Instant now) {
        long closedCount = 0;
        for (Map.Entry<String, PendingSpan> entry : pending.entrySet()) {
            if (!entry.getValue().deadline().isAfter(now)) {
                PendingSpan removed = pending.remove(entry.getKey());
                if (removed != null) {
                    closeSpan(removed.span(), true);
                    closedCount++;
                }
            }
        }
        if (closedCount > 0) {
            abnormalEndCount.addAndGet(closedCount);
        }
        return closedCount;
    }

    public long abnormalEndCount() {
        return abnormalEndCount.get();
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Turn 兜底超时（system_param 可配）
     */
    public Duration turnTimeout() {
        return Duration.ofMinutes(systemParamService.getLong("observe.span.closure.turnTimeoutMin", 10));
    }

    /**
     * Task 兜底超时（system_param 可配）
     */
    public Duration taskTimeout() {
        return Duration.ofHours(systemParamService.getLong("observe.span.closure.taskTimeoutHour", 48));
    }

    private void closeSpan(Span span, boolean abnormal) {
        try {
            span.setAttribute(ATTR_ABNORMAL_END, abnormal);
            span.end();
        } catch (Exception e) {
            log.warn("Span 闭合异常（不外溢） abnormal={}", abnormal, e);
        }
    }

    private void scanLoop(long scanIntervalSec) {
        while (!closed) {
            try {
                Thread.sleep(Duration.ofSeconds(scanIntervalSec));
                long forced = forceCloseExpired(clock.instant());
                if (forced > 0) {
                    log.warn("闭合注册表超时强制闭合 {} 个长生命周期 Span（abnormal_end）", forced);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("闭合注册表扫描异常（继续）", e);
            }
        }
    }
}
