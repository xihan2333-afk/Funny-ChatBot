package cn.edu.scut.chatbot.observability.replay;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评估与重放批次的共用并发闸（observe.replay.maxConcurrent——契约第 2/5 节
 * 「同闸」语义）：daily / manual / replay / safety 四类批次共享同一道上限，
 * 进程内计数（单实例部署形态——多实例时按实例各自上限）。
 */
@Component
public class ReplayConcurrencyGate {

    private final SystemParamService params;

    private final AtomicInteger running = new AtomicInteger();

    public ReplayConcurrencyGate(SystemParamService params) {
        this.params = params;
    }

    /**
     * 占位（占满返回 false）；调用方负责 release（finally）
     */
    public boolean tryAcquire() {
        int maxConcurrent = Math.max(1, params.getInt("observe.replay.maxConcurrent", 2));
        while (true) {
            int current = running.get();
            if (current >= maxConcurrent) {
                return false;
            }
            if (running.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        running.decrementAndGet();
    }
}
