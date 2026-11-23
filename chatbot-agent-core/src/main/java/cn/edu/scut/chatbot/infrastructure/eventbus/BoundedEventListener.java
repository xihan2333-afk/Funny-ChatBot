package cn.edu.scut.chatbot.infrastructure.eventbus;

import cn.edu.scut.chatbot.loop.events.LoopEvent;
import cn.edu.scut.chatbot.loop.events.LoopEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 循环事件监听器外壳（契约 loop-kernel.md 第 3 节、T015）：实现 loop 的监听器接口，
 * 只做快速入队——有界队列 queue.offer（满则丢弃并计数），慢操作由后台消费者消化。
 *
 * <p>监听器两条纪律的结构保证：
 * <ul>
 *   <li>只做快速入队：onEvent 里只有 offer（非阻塞），绝不落地、绝不 IO</li>
 *   <li>异常不外溢：入队与后台消费的异常都吞掉记日志，不影响循环</li>
 * </ul>
 *
 * <p>丢弃计数与队列水位是观测面（外壳队列满则丢弃计数——观测事件允许丢，
 * 主流程永远不受慢消费者拖累）。
 */
public class BoundedEventListener implements LoopEventListener {

    private static final Logger log = LoggerFactory.getLogger(BoundedEventListener.class);

    /**
     * 有界队列容量（观测事件的背压上限：满则丢弃计数——消费端缺位不影响主流程）
     */
    private static final int QUEUE_CAPACITY = 4096;

    private final LinkedBlockingQueue<LoopEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * 队列满而丢弃的事件数（观测指标：持续增长说明消费端缺位或过慢）
     */
    private final AtomicLong droppedCount = new AtomicLong();

    /**
     * 已交付给后台消费者的事件总数
     */
    private final AtomicLong deliveredCount = new AtomicLong();

    /**
     * 后台消费者（可多个；典型是观测桥把事件翻译成 Span/落库——2.5 feature）
     */
    private final List<LoopEventListener> backgroundConsumers = new CopyOnWriteArrayList<>();

    private final Thread consumerThread;

    /**
     * 生产构造：后台消费线程随建随启
     */
    public BoundedEventListener() {
        this(true);
    }

    /**
     * @param startConsumer false 时不启后台消费（测试考「队列满丢弃计数」的确定性形态：
     *                      无人消费队列只涨，不依赖消费时序）
     */
    public BoundedEventListener(boolean startConsumer) {
        this.consumerThread = startConsumer
                ? Thread.ofVirtual().name("loop-event-drain").start(this::drainLoop)
                : null;
    }

    /**
     * 注册后台消费者（2.5 观测桥挂点）
     */
    public void addBackgroundConsumer(LoopEventListener consumer) {
        backgroundConsumers.add(consumer);
    }

    /**
     * 快速入队：满则丢弃计数（绝不阻塞循环线程）
     */
    @Override
    public void onEvent(LoopEvent event) {
        if (!queue.offer(event)) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped % 100 == 1) {
                // 首次与每百次记一条：观测事件丢弃是允许的，但持续丢弃要有日志可查
                log.warn("循环事件队列已满，丢弃观测事件（累计 {} 条） errorCode=engine.event.dropped",
                        dropped);
            }
        }
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public long deliveredCount() {
        return deliveredCount.get();
    }

    /**
     * 后台消费循环：单命名虚拟线程逐条派发给消费者；消费者异常不外溢
     */
    private void drainLoop() {
        while (true) {
            try {
                LoopEvent event = queue.poll(60, TimeUnit.SECONDS);
                if (event == null) {
                    continue;
                }
                deliveredCount.incrementAndGet();
                for (LoopEventListener consumer : backgroundConsumers) {
                    try {
                        consumer.onEvent(event);
                    } catch (Exception e) {
                        log.warn("循环事件后台消费者异常（不外溢）", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("循环事件后台消费循环异常（继续）", e);
            }
        }
    }
}
