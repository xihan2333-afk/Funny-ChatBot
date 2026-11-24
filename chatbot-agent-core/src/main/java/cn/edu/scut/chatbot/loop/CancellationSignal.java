package cn.edu.scut.chatbot.loop;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消唯一通道（契约 loop-kernel.md 第 1 节）：置位后 StreamFn 实现方必须停止读流、
 * 以 Failed(aborted) 终止。任何组件不得持有能 interrupt 推理线程的引用——取消只经本信号
 * 传入（宪法「推理一旦开始不被抢占」）。
 *
 * <p>卡死检测（N 秒无增量）、Task 取消、群暂停、群销毁四种取消源汇到同一个信号面。
 */
public final class CancellationSignal {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public static CancellationSignal create() {
        return new CancellationSignal();
    }

    /**
     * 置位（幂等；多取消源重复置位无害）
     */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
