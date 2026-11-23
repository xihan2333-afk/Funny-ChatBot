package cn.edu.scut.chatbot.fixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 测试固定时钟（宪法「时间与 ID」节：时间逻辑必须可注入固定时钟测试）。
 *
 * <p>默认 2026-05-01T08:00:00（Asia/Shanghai），可 {@link #advanceMillis} / {@link #setTo}
 * 拨动——调度器到期扫描、冷却顺延、时效判定等用例靠拨钟快进，不真等墙钟。
 *
 * <p>autoAdvanceMillis（默认 0 不自动前进）：每次读取 millis() 自动前进指定毫秒——
 * 雪花发号器「回拨小于 5ms 等待追上」分支的考法：先 setTo 回拨、再开启自动前进，
 * 模拟 parkNanos 等待期间物理时钟恢复走动。
 */
public class FixedClock extends Clock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private long currentMillis;

    private long autoAdvanceMillis = 0;

    public FixedClock() {
        this(ZonedDateTime.of(2026, 5, 1, 8, 0, 0, 0, ZONE).toInstant().toEpochMilli());
    }

    public FixedClock(long initialMillis) {
        this.currentMillis = initialMillis;
    }

    /**
     * 拨快指定毫秒（快进到未来时刻）
     */
    public void advanceMillis(long millis) {
        currentMillis += millis;
    }

    /**
     * 直接设定时刻（回拨场景：setTo(now - 3) 模拟时钟回退）
     */
    public void setTo(long millis) {
        this.currentMillis = millis;
    }

    /**
     * 开启每次读取自动前进（模拟等待期间物理时钟走动）
     */
    public void setAutoAdvanceMillis(long perReadMillis) {
        this.autoAdvanceMillis = perReadMillis;
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        long value = currentMillis;
        if (autoAdvanceMillis > 0) {
            currentMillis += autoAdvanceMillis;
        }
        return Instant.ofEpochMilli(value);
    }

    public long currentMillis() {
        long value = currentMillis;
        if (autoAdvanceMillis > 0) {
            currentMillis += autoAdvanceMillis;
        }
        return value;
    }

    @Override
    public long millis() {
        return currentMillis();
    }
}
