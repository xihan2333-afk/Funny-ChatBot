package cn.edu.scut.chatbot.bootstrap;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.engine.EngineErrorCodes;

import java.time.Clock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 自研雪花发号器（设计方案 3.4.5）：64 位 = 1 符号位 + 41 位毫秒时间戳（自定基准起算）
 * + 5 位机器号（agent-core 默认 1，与接入层默认 2 区分）+ 12 位序列（同毫秒 4096 个）。
 *
 * <p>turnId / taskId / runId / outboxId / messageId 全部经本组件发放，且发放先于落库
 * （宪法「时间与 ID」节——进程外可见 ID 的唯一来源）。
 *
 * <p>时钟回拨处理（宁可失败不可重复）：回拨小于 5ms 等待时钟追上后继续发号；
 * 超过 5ms 或等待后仍未追上，抛 {@link NonRetryableException} 拒绝发号。
 *
 * <p>时间源刻意用系统时钟而非业务 Clock bean——发号算法依赖真实物理时刻
 * （方案 3.4.5 的唯一例外条款）；测试注入可控时钟驱动回拨分支。
 */
public class IdGenerator {

    /**
     * 时间戳基准：2026-01-01T00:00:00Z（项目立项年代），41 位自此起算约 69 年不溢出
     */
    private static final long CUSTOM_EPOCH_MILLIS = 1767225600000L;

    private static final int WORKER_ID_BITS = 5;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;

    /**
     * 时钟回拨容忍上限：小于该值等待追上，超过拒绝发号
     */
    private static final long MAX_BACKWARD_TOLERANCE_MILLIS = 5;

    private final int workerId;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private long lastTimestamp = -1;
    private long sequence = 0;

    public IdGenerator(int workerId, Clock clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "雪花机器号必须在 0~" + MAX_WORKER_ID + " 内（5 位），当前值：" + workerId);
        }
        this.workerId = workerId;
        this.clock = clock;
    }

    /**
     * 发放下一个全局唯一 ID。临界区用 ReentrantLock（synchronized 长块禁区纪律），
     * 临界区内只有内存读写与极短等待，无长阻塞。
     */
    public long nextId() {
        lock.lock();
        try {
            long now = currentTimestamp();
            if (now < lastTimestamp) {
                now = waitForClockToCatchUp(now);
            }
            if (now == lastTimestamp) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) {
                    now = waitUntilNextMillis(now);
                }
            } else {
                sequence = 0;
            }
            lastTimestamp = now;
            return (now << TIMESTAMP_SHIFT) | ((long) workerId << WORKER_ID_SHIFT) | sequence;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 发放十进制字符串形态（信封字段、转录 messageId 等字符串场景）
     */
    public String nextIdString() {
        return Long.toUnsignedString(nextId());
    }

    private long currentTimestamp() {
        return clock.millis() - CUSTOM_EPOCH_MILLIS;
    }

    private long waitForClockToCatchUp(long now) {
        long backwardMillis = lastTimestamp - now;
        if (backwardMillis > MAX_BACKWARD_TOLERANCE_MILLIS) {
            throw new NonRetryableException(EngineErrorCodes.ID_CLOCK_BACKWARD,
                    "雪花发号器检测到时钟回拨 " + backwardMillis + "ms，超过容忍上限 "
                            + MAX_BACKWARD_TOLERANCE_MILLIS + "ms，拒绝发号（宁可失败不可重复）");
        }
        LockSupport.parkNanos(backwardMillis * 1_000_000);
        long recovered = currentTimestamp();
        if (recovered < lastTimestamp) {
            throw new NonRetryableException(EngineErrorCodes.ID_CLOCK_BACKWARD,
                    "雪花发号器等待 " + backwardMillis + "ms 后时钟仍未追上，拒绝发号（宁可失败不可重复）");
        }
        return recovered;
    }

    private long waitUntilNextMillis(long now) {
        long next = currentTimestamp();
        while (next <= now) {
            LockSupport.parkNanos(200_000);
            next = currentTimestamp();
        }
        return next;
    }
}
