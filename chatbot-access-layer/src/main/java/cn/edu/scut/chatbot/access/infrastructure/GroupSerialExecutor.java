package cn.edu.scut.chatbot.access.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 同群串行泵（宪法第四条：同群事件严格串行、异群并行；出流串行由接入层 Sender 保证，
 * 与 agent-core 侧消费泵模式同构、各自实现是既定纪律——该组件不进 contract）。
 *
 * <p>GroupKey = botId + groupId 路由：每群一条单虚拟线程泵（不池化），submit 只入队分发、
 * 立即返回；泵线程循环取任务执行，同群任务天然按提交顺序串行，异群各泵互不等待。
 * groupId 为空（私聊 / 全局事件）以 userId 作串行键，两者皆空用常量 {@code __global__}
 * 保证有键可分（出方向第一版只有群消息，该分支为形态完备性预留）。
 *
 * <p>泵内任务异常不中断泵（投递任务自带兜底与回执，异常吞掉记日志）；
 * 阻塞等待全部 j.u.c（synchronized 长块禁区纪律）。
 */
@Component
public class GroupSerialExecutor {

    /**
     * groupId 与 userId 均为空时的串行键（保证有键可分）
     */
    public static final String GLOBAL_SERIAL_KEY = "__global__";

    private static final Logger log = LoggerFactory.getLogger(GroupSerialExecutor.class);

    /**
     * 串行键 → 该群的单队列；队列存在即泵已建（创建用 putIfAbsent 保证单泵）
     */
    private final ConcurrentHashMap<String, BlockingQueue<Runnable>> groupQueues = new ConcurrentHashMap<>();

    /**
     * 串行键数量上限：串行键来自流内数据（botId/groupId），上限闸防异常数据无界造泵（宪法「无界并发必须有闸」）
     */
    private static final int MAX_SERIAL_KEYS = 1024;

    /**
     * 已入队 + 正在执行的任务数（排空等待的口径：归零即全部泵任务完成）
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlightTasks =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * submit 只入队分发（消费线程纪律：不在此做任何投递业务），任务完成由 future 通知
     */
    public CompletableFuture<Void> submit(String botId, String groupId, String userId, Runnable task) {
        String serialKey = serialKeyOf(botId, groupId, userId);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Runnable trackedTask = () -> {
            try {
                task.run();
                completion.complete(null);
            } catch (Throwable t) {
                // 投递任务应自带兜底（失败回执），到这里的异常属任务自身 bug：记日志不中断泵
                log.error("群串行泵内任务异常（serialKey={}）", serialKey, t);
                completion.completeExceptionally(t);
            } finally {
                inFlightTasks.decrementAndGet();
            }
        };
        inFlightTasks.incrementAndGet();
        if (groupQueues.size() >= MAX_SERIAL_KEYS && !groupQueues.containsKey(serialKey)) {
            throw new IllegalStateException("串行键数量超过上限 " + MAX_SERIAL_KEYS
                    + "（串行键异常膨胀，拒绝建新泵——上限闸纪律）");
        }
        groupQueues
                .computeIfAbsent(serialKey, key -> {
                    BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
                    Thread.ofVirtual().name("group-pump-" + key).start(() -> pumpLoop(key, queue));
                    return queue;
                })
                .offer(trackedTask);
        return completion;
    }

    /**
     * 优雅停机入口：停泵即清空队列
     */
    public void shutdown() {
        groupQueues.clear();
    }

    /**
     * 当前活跃串行键数量（排空等待的观察口径）
     */
    public int activeSerialKeyCount() {
        return groupQueues.size();
    }

    /**
     * 等待全部队列排空且没有在跑的任务（优雅停机的排空口径：泵内任务完成即 XACK，
     * 未完成的留给重启后 pending 重投）。超时返回 false。
     */
    public boolean awaitDrain(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        while (System.nanoTime() < deadline) {
            boolean allIdle = inFlightTasks.get() == 0
                    && groupQueues.values().stream().allMatch(BlockingQueue::isEmpty);
            if (allIdle) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return inFlightTasks.get() == 0
                && groupQueues.values().stream().allMatch(BlockingQueue::isEmpty);
    }

    private void pumpLoop(String serialKey, BlockingQueue<Runnable> queue) {
        try {
            while (true) {
                Runnable task = queue.poll(60, TimeUnit.SECONDS);
                if (task != null) {
                    task.run();
                } else if (!groupQueues.containsKey(serialKey)) {
                    // 停机后队列被移除：泵退出
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String serialKeyOf(String botId, String groupId, String userId) {
        String scope = groupId != null && !groupId.isBlank() ? groupId
                : (userId != null && !userId.isBlank() ? userId : GLOBAL_SERIAL_KEY);
        return botId + ":" + scope;
    }
}
