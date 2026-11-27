// 守护纪律：策略数据面频控窗口计数、域名黑名单与越权小时桶计数（宪法第六条、security-gate 契约第 4、6 节）
package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatePolicyServiceTest {

    private BlacklistMapper blacklistMapper;
    private GroupMapper groupMapper;
    private SystemParamService systemParams;
    private StringRedisTemplate redis;
    private Map<String, Long> counters;
    private Clock clock;
    private GatePolicyServiceImpl service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        blacklistMapper = mock(BlacklistMapper.class);
        groupMapper = mock(GroupMapper.class);
        systemParams = mock(SystemParamService.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        counters = new HashMap<>();

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long next = counters.getOrDefault(key, 0L) + 1L;
            counters.put(key, next);
            return next;
        });
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        clock = Clock.fixed(Instant.parse("2026-11-26T06:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new GatePolicyServiceImpl(blacklistMapper, groupMapper, systemParams, redis, clock);
    }

    @Test
    void 域名黑名单匹配精确域名与子域名() {
        when(systemParams.getString("security.domain.blacklist", "[]"))
                .thenReturn("[\"evil.example.com\", \"spam.test\"]");

        assertTrue(service.isDomainBlocked("evil.example.com"));
        assertTrue(service.isDomainBlocked("SUB.EVIL.EXAMPLE.COM"));
        assertTrue(service.isDomainBlocked("a.b.spam.test"));
        assertFalse(service.isDomainBlocked("notevil.example.com"));
        assertFalse(service.isDomainBlocked("safe.org"));
    }

    @Test
    void 重任务与runCode频控按日窗口计数超限拒绝() {
        when(systemParams.getInt("security.freq.heavyTaskPerUserDaily", 10)).thenReturn(2);
        when(systemParams.getInt("security.freq.runCodePerGroupDaily", 20)).thenReturn(1);

        assertTrue(service.checkHeavyTaskQuota("qq", "b1", "g1", "u1").allowed());
        assertTrue(service.checkHeavyTaskQuota("qq", "b1", "g1", "u1").allowed());
        GatePolicyService.FrequencyVerdict third = service.checkHeavyTaskQuota("qq", "b1", "g1", "u1");
        assertFalse(third.allowed());
        assertEquals(3, third.used());
        assertEquals(2, third.limit());

        assertTrue(service.checkRunCodeQuota("qq", "b1", "g1").allowed());
        assertFalse(service.checkRunCodeQuota("qq", "b1", "g1").allowed());
    }

    @Test
    void 同用户越权小时桶计数达到阈值返回越界信号() {
        when(systemParams.getInt("security.freq.mandateDenyPerHour", 10)).thenReturn(3);

        assertTrue(service.recordMandateDenied("u-attack").allowed());
        assertTrue(service.recordMandateDenied("u-attack").allowed());
        GatePolicyService.FrequencyVerdict hit = service.recordMandateDenied("u-attack");
        assertFalse(hit.allowed());
        assertEquals(3, hit.used());
        assertEquals(3, hit.limit());
    }

    @Test
    void 重任务群级每日上限与跨日拨钟重置窗口及黑名单查询异常降级() {
        when(systemParams.getInt("security.freq.heavyTaskPerGroupDaily", 100)).thenReturn(1);
        when(systemParams.getInt("security.freq.heavyTaskPerUserDaily", 10)).thenReturn(1);

        var mutableClock = new Clock() {
            private Instant now = Instant.parse("2026-11-26T15:59:00Z"); // 23:59 CST

            void advanceToNextDay() {
                this.now = Instant.parse("2026-11-26T16:01:00Z"); // 次日 00:01 CST
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
        };
        GatePolicyServiceImpl timedService = new GatePolicyServiceImpl(
                blacklistMapper, groupMapper, systemParams, redis, mutableClock);

        assertTrue(timedService.checkHeavyTaskGroupQuota("qq", "b1", "g1").allowed());
        assertFalse(timedService.checkHeavyTaskGroupQuota("qq", "b1", "g1").allowed());
        assertTrue(timedService.checkHeavyTaskQuota("qq", "b1", "g1", "u1").allowed());
        assertFalse(timedService.checkHeavyTaskQuota("qq", "b1", "g1", "u1").allowed());

        // 拨钟跨过零点：新日期窗口计数重新放行
        mutableClock.advanceToNextDay();
        assertTrue(timedService.checkHeavyTaskGroupQuota("qq", "b1", "g1").allowed());
        assertTrue(timedService.checkHeavyTaskQuota("qq", "b1", "g1", "u1").allowed());

        // 黑名单 DB 异常不误伤正常用户；空 userId 直接未命中
        assertFalse(timedService.isBlacklisted("qq", "b1", "g1", " "));
        when(blacklistMapper.selectHit("qq", "b1", "g1", "u-err")).thenThrow(new RuntimeException("db down"));
        assertFalse(timedService.isBlacklisted("qq", "b1", "g1", "u-err"));
    }
}
