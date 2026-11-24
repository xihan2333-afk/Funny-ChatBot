package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.loop.CancellationSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker 取消信号注册表：生命周期事件（任务取消/群暂停/群销毁）置位信号的唯一面。
 * 独立成组件避免 TaskCoordinator 与 WorkerLauncher 相互依赖
 * （TaskCoordinator 停任务要置信号、WorkerLauncher 启动要注册信号——注册表两边都不认识对方）。
 *
 * <p>取消只经 CancellationSignal 传入 StreamFn（不 interrupt、不持线程引用——宪法实现规约）；
 * 无活跃 Worker 时置位无害。
 */
@Component
public class WorkerSignalRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerSignalRegistry.class);

    private final Map<Long, CancellationSignal> activeSignals = new ConcurrentHashMap<>();

    /**
     * Worker 启动时注册信号（WorkerRunner 运行期间有效）
     */
    public void register(long taskId, CancellationSignal signal) {
        activeSignals.put(taskId, signal);
    }

    /**
     * Worker 结束时注销（finally 路径）
     */
    public void unregister(long taskId) {
        activeSignals.remove(taskId);
    }

    /**
     * 置位该 Task 的取消信号（Task 取消/群暂停/群销毁调用；无活跃 Worker 时无害）
     */
    public void cancelWorker(long taskId) {
        CancellationSignal signal = activeSignals.get(taskId);
        if (signal != null) {
            signal.cancel();
            log.info("Worker 取消信号置位 taskId={}", taskId);
        }
    }
}
