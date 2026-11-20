package cn.edu.scut.chatbot.access.fixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可拨动的固定时钟（测试假件）：默认停在给定时刻，测试可手动 advance 前进。
 *
 * <p>供集成测试装配「固定 Clock」——receivedAt / attemptedAt 这类时间戳变得可精确断言；
 * 媒体限时、pending 空闲这类时长逻辑可以拨钟快进而不真等。
 */
public final class SteadyClock extends Clock {

    private volatile Instant now;

    public SteadyClock(long epochMillis) {
        this.now = Instant.ofEpochMilli(epochMillis);
    }

    /**
     * 时间前进指定毫秒（不回拨——假件只服务测试快进）
     */
    public void advanceMillis(long millis) {
        now = now.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("Asia/Shanghai");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
