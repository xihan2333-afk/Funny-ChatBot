package cn.edu.scut.chatbot.access.bootstrap;

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;

import java.time.Clock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 自研雪花发号器（设计方案 3.4.5，research.md R3 决策）：64 位 =
 * 1 符号位（恒 0）+ 41 位毫秒时间戳（自定基准起算，可用约 69 年）
 * + 5 位机器号（接入层默认 2，与 agent-core 的默认 1 区分，同机部署不撞号）+ 12 位序列（同毫秒 4096 个）。
 *
 * <p>eventId 写入事件信封进入 Redis Stream 与 Agent 侧，属进程外可见 ID，
 * 按宪法时间与 ID 规约必须走雪花；发放先于 XADD。
 *
 * <p>时钟回拨处理（宁可失败不可重复）：回拨小于 5ms 等待时钟追上后继续发号；
 * 超过 5ms 或等待后仍未追上，抛 {@link NonRetryableException} 拒绝发号。
 *
 * <p>发号算法的时间源是注入的 {@link java.time.Clock}——生产装配传系统时钟
 * （算法时间独立于业务 Clock bean，方案 3.4.5 的唯一例外条款），测试注入固定 / 可拨动时钟。
 */
public class SnowflakeIdGenerator {

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

    /**
     * 上次发号的毫秒时间戳（基准换算后）
     */
    private long lastTimestamp = -1;
    /**
     * 当前毫秒内已用序列号
     */
    private long sequence = 0;

    public SnowflakeIdGenerator(int workerId, Clock clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "雪花机器号必须在 0~" + MAX_WORKER_ID + " 内（5 位），当前值：" + workerId);
        }
        this.workerId = workerId;
        this.clock = clock;
    }

    /**
     * 发放下一个全局唯一 ID。
     *
     * <p>临界区用 ReentrantLock（synchronized 长块禁区纪律，j.u.c 感知虚拟线程）；
     * 临界区内只有内存读写与极短等待（回拨容忍 ≤5ms、序列耗尽等到下一毫秒），无长阻塞。
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
                    // 当前毫秒的 4096 个序列号已用尽，等到下一毫秒再发
                    now = waitUntilNextMillis(now);
                }
            } else {
                sequence = 0;
            }
            lastTimestamp = now;
            return ((now) << TIMESTAMP_SHIFT) | ((long) workerId << WORKER_ID_SHIFT) | sequence;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 基准换算后的当前毫秒时间戳
     */
    private long currentTimestamp() {
        return clock.millis() - CUSTOM_EPOCH_MILLIS;
    }

    /**
     * 时钟回拨处理：小于 5ms 时等待追上。等待后仍未追上（测试固定时钟或时钟继续走低）
     * 视为回拨超限，拒绝发号。
     */
    private long waitForClockToCatchUp(long now) {
        long backwardMillis = lastTimestamp - now;
        if (backwardMillis > MAX_BACKWARD_TOLERANCE_MILLIS) {
            throw new NonRetryableException(ErrorCodes.ADAPTER_ID_CLOCK_BACKWARD,
                    "雪花发号器检测到时钟回拨 " + backwardMillis + "ms，超过容忍上限 "
                            + MAX_BACKWARD_TOLERANCE_MILLIS + "ms，拒绝发号（宁可失败不可重复）");
        }
        LockSupport.parkNanos(backwardMillis * 1_000_000);
        long recovered = currentTimestamp();
        if (recovered < lastTimestamp) {
            throw new NonRetryableException(ErrorCodes.ADAPTER_ID_CLOCK_BACKWARD,
                    "雪花发号器等待 " + backwardMillis + "ms 后时钟仍未追上，拒绝发号（宁可失败不可重复）");
        }
        return recovered;
    }

    /**
     * 序列号耗尽时自旋等待到下一毫秒（等待时长恒小于 1ms）
     */
    private long waitUntilNextMillis(long now) {
        long next = currentTimestamp();
        while (next <= now) {
            LockSupport.parkNanos(200_000);
            next = currentTimestamp();
        }
        return next;
    }
}
