package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.effect.EffectHandler;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.loop.CancellationSignal;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Worker 启动器（spec US4、research R7）：workerLaunch Effect 处理器——先 acquire 全局
 * Semaphore（默认 2、system_param 可配，提交侧闸拿不到阻塞不拒绝——Effects 行已置 running，
 * 队列语义是最终执行）；拿到后 Thread.ofVirtual 启动 Worker Run。
 *
 * <p>取消传递：Task 取消/群暂停/群销毁时对本注册表的信号置位（不 interrupt、不持线程引用
 * ——取消只经 CancellationSignal 传入 StreamFn，宪法实现规约）。
 * 阻塞等信号量期间进程崩溃：启动恢复把 running 置回 pending，workerLaunch 重新排队不丢任务。
 */
@Component
public class WorkerLauncher implements EffectHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkerLauncher.class);

    private final WorkerRunner workerRunner;
    private final SystemParamService systemParamService;

    /**
     * 全局并发闸（提交侧；上限启动时读一次——固定许可数，动态调整会许可漂移）
     */
    private final Semaphore semaphore;

    /**
     * taskId → 取消信号注册表（生命周期事件置位的唯一面）
     */
    private final Map<Long, CancellationSignal> activeSignals = new ConcurrentHashMap<>();

    public WorkerLauncher(WorkerRunner workerRunner, SystemParamService systemParamService) {
        this.workerRunner = workerRunner;
        this.systemParamService = systemParamService;
        // 并发闸上限启动时读一次（热变更演进点：动态上限需自定义计数闸，当前固定防许可漂移）
        this.semaphore = new Semaphore(Math.max(1, systemParamService.getInt("worker.max_concurrency", 2)));
    }

    @Override
    public String kind() {
        return EffectsQueueDO.KIND_WORKER_LAUNCH;
    }

    @Override
    public void handle(EffectsQueueDO effect) throws Exception {
        long taskId;
        try {
            JsonNode payload = JsonMappers.shared().readTree(effect.getPayload());
            taskId = payload.path("taskId").asLong();
        } catch (Exception e) {
            throw new IllegalArgumentException("workerLaunch 载荷损坏 effectId=" + effect.getId(), e);
        }

        // 提交侧闸：acquire 一个许可（拿不到阻塞等待——忙不是故障，不拒绝）
        semaphore.acquire();

        CancellationSignal signal = CancellationSignal.create();
        activeSignals.put(taskId, signal);
        Thread.ofVirtual().name("worker-" + taskId).start(() -> {
            try {
                workerRunner.run(taskId, signal);
            } catch (Exception e) {
                log.error("Worker 运行异常（结论按失败回传） taskId={}", taskId, e);
                workerRunner.concludeFailed(taskId, "engine.task.worker_crashed:" + e.getMessage());
            } finally {
                activeSignals.remove(taskId);
                semaphore.release();
            }
        });
        // 处理器返回即 Effect 行删除；Worker 自身进度经 task_execution_log 留痕
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

    /**
     * 等待全局闸可用（测试观察口径）
     */
    public boolean tryAcquireForTest(int permits, long timeoutMillis) throws InterruptedException {
        return semaphore.tryAcquire(permits, timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
