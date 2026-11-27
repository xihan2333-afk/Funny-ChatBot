package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.engine.events.EngineEvent;
import cn.edu.scut.chatbot.engine.events.EngineEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 引擎事件桥外壳（observability-bridge 契约第 7 节铁律、T012）：BoundedEventListener
 * 同构的有界队列外壳，注册为 EngineEvent 消费者——与循环事件外壳同一套监听器纪律：
 * <ol>
 *   <li>只做快速入队：onEvent 只有 offer（非阻塞），绝不 IO、绝不落地；</li>
 *   <li>异常不外溢：入队与消费的异常吞掉记日志；</li>
 *   <li>观测允许丢、业务不允许卡：队列满丢弃计数（喂给导出管道异常告警探针）。</li>
 * </ol>
 *
 * <p>与循环事件外壳的差异：引擎事件外壳是应用级单例（引擎事件不分 Run，turn/task
 * 长生命周期事件与 Effects 事件都进同一队列），随应用关闭而排空退出。
 */
public final class EngineEventBridgeShell implements EngineEventListener {

    private static final Logger log = LoggerFactory.getLogger(EngineEventBridgeShell.class);

    /**
     * 有界队列容量（满则丢弃计数——观测事件的背压上限）
     */
    private static final int QUEUE_CAPACITY = 4096;

    private final LinkedBlockingQueue<EngineEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * 队列满丢弃计数（ExportHealthMetrics 聚合——喂「导出管道异常」告警）
     */
    private final AtomicLong droppedCount = new AtomicLong();

    private final ExportHealthMetrics health;

    private final List<EngineEventListener> backgroundConsumers = new CopyOnWriteArrayList<>();

    private final Thread consumerThread;

    private volatile boolean closed = false;

    /**
     * @param health 丢弃计数上报面（可为 null——独立测试形态不上报）
     */
    public EngineEventBridgeShell(ExportHealthMetrics health) {
        this.health = health;
        this.consumerThread = Thread.ofVirtual().name("engine-event-bridge-drain").start(() -> drainLoop());
    }

    /**
     * 注册后台消费者（Span 翻译器——慢操作由本外壳的队列缓冲隔离在引擎线程之外）
     */
    public void addBackgroundConsumer(EngineEventListener consumer) {
        backgroundConsumers.add(consumer);
    }

    /**
     * 关闭：排空残余事件后停消费线程（应用关闭时调用）
     */
    public void close() {
        closed = true;
        consumerThread.interrupt();
    }

    @Override
    public void onEvent(EngineEvent event) {
        if (!queue.offer(event)) {
            long dropped = droppedCount.incrementAndGet();
            if (health != null) {
                health.recordEventDrop(1);
            }
            if (dropped % 100 == 1) {
                log.warn("引擎事件队列已满，丢弃观测事件（累计 {} 条） errorCode=observe.export_dropped",
                        dropped);
            }
        }
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    private void drainLoop() {
        while (true) {
            try {
                EngineEvent event = closed ? queue.poll() : queue.poll(60, TimeUnit.SECONDS);
                if (event == null) {
                    if (closed) {
                        return;
                    }
                    continue;
                }
                for (EngineEventListener consumer : backgroundConsumers) {
                    try {
                        consumer.onEvent(event);
                    } catch (Exception e) {
                        log.warn("引擎事件桥后台消费者异常（不外溢）", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (closed) {
                    continue;
                }
            } catch (Exception e) {
                log.warn("引擎事件桥消费循环异常（继续）", e);
            }
        }
    }
}
