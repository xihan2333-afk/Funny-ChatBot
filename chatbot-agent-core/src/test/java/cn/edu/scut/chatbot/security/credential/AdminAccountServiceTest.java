// 守护纪律：管理平台单账号 BCrypt 校验、会话过期与续期上限、改密全端下线与防爆破（admin-auth 契约第 1 节）
package cn.edu.scut.chatbot.security.credential;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.management.AdminAccountService;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAccountServiceTest {

    private AdminAccountMapper mapper;
    private StringRedisTemplate redis;
    private SystemParamService systemParams;
    private SecurityEventRecorder recorder;
    private Map<String, String> kvStore;
    private Map<String, Set<String>> setStore;
    private MutableClock clock;
    private AdminAccountServiceImpl service;
    private AdminAccountDO account;

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void plusHours(long h) {
            this.instant = this.instant.plus(Duration.ofHours(h));
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
            return instant;
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mapper = mock(AdminAccountMapper.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        systemParams = mock(SystemParamService.class);
        recorder = mock(SecurityEventRecorder.class);

        kvStore = new HashMap<>();
        setStore = new HashMap<>();

        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> kvStore.get(inv.getArgument(0)));
        doAnswer(inv -> {
            kvStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long next = Long.parseLong(kvStore.getOrDefault(k, "0")) + 1L;
            kvStore.put(k, String.valueOf(next));
            return next;
        });
        when(redis.delete(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            boolean removed = kvStore.remove(k) != null;
            removed |= setStore.remove(k) != null;
            return removed;
        });
        when(redis.delete(anyCollection())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            long cnt = 0;
            for (String k : keys) {
                if (kvStore.remove(k) != null) {
                    cnt++;
                }
            }
            return cnt;
        });
        when(setOps.add(anyString(), any())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            Object v = inv.getArgument(1);
            Set<String> bucket = setStore.computeIfAbsent(k, ignored -> new HashSet<>());
            if (v instanceof String[] arr) {
                bucket.addAll(Arrays.asList(arr));
            } else if (v != null) {
                bucket.add(v.toString());
            }
            return 1L;
        });
        when(setOps.members(anyString())).thenAnswer(inv -> setStore.getOrDefault(inv.getArgument(0), Set.of()));
        when(setOps.remove(anyString(), any())).thenAnswer(inv -> {
            Set<String> s = setStore.get(inv.getArgument(0));
            return s != null && s.remove(inv.getArgument(1)) ? 1L : 0L;
        });

        when(systemParams.getInt("security.admin.sessionTtlHours", 12)).thenReturn(12);
        when(systemParams.getInt("security.admin.sessionMaxHours", 24)).thenReturn(24);
        when(systemParams.getInt("security.admin.maxLoginFails", 5)).thenReturn(3);

        account = new AdminAccountDO();
        account.setId(1);
        account.setUsername("admin");
        account.setPasswordHash(new BCryptPasswordEncoder().encode("InitialPass123"));
        account.setMustChange(1);

        when(mapper.selectByUsername("admin")).thenReturn(account);
        when(mapper.selectPrimary()).thenReturn(account);
        doAnswer(inv -> {
            account.setPasswordHash(inv.getArgument(1));
            account.setMustChange(inv.getArgument(2));
            return 1;
        }).when(mapper).updatePassword(eq(1), anyString(), anyInt());

        clock = new MutableClock(Instant.parse("2026-11-26T00:00:00Z"));
        service = new AdminAccountServiceImpl(mapper, redis, systemParams, recorder, clock);
    }

    @Test
    void 首次登录签发会话且带mustChange_改密后旧口令失效且全端会话下线() {
        AdminAccountService.LoginResult s1 = service.login("admin", "InitialPass123");
        AdminAccountService.LoginResult s2 = service.login("admin", "InitialPass123");
        assertTrue(s1.mustChange());
        assertTrue(service.verifySession(s1.token()).isPresent());
        assertTrue(service.verifySession(s2.token()).isPresent());

        // 改密
        service.changePassword("InitialPass123", "NewStrongPass456");
        assertFalse(service.mustChangePending());

        // 全端下线：两个旧会话均失效
        assertTrue(service.verifySession(s1.token()).isEmpty());
        assertTrue(service.verifySession(s2.token()).isEmpty());

        // 旧口令失效、新口令可登录且 mustChange=false
        assertThrows(CommandRejectedException.class, () -> service.login("admin", "InitialPass123"));
        AdminAccountService.LoginResult s3 = service.login("admin", "NewStrongPass456");
        assertFalse(s3.mustChange());
        verify(recorder, atLeast(3)).record(eq(SecurityEventKind.ADMIN_OPERATE), any(), any(), isNull());
    }

    @Test
    void 会话超过最大续期上限自动拒绝() {
        AdminAccountService.LoginResult res = service.login("admin", "InitialPass123");
        clock.plusHours(10);
        assertTrue(service.verifySession(res.token()).isPresent());

        clock.plusHours(15); // 累计 25 小时 > 24 小时最大上限
        assertTrue(service.verifySession(res.token()).isEmpty());
    }

    @Test
    void 连续登录失败达到阈值触发防爆破锁定() {
        assertThrows(CommandRejectedException.class, () -> service.login("admin", "wrong1"));
        assertThrows(CommandRejectedException.class, () -> service.login("admin", "wrong2"));
        assertThrows(CommandRejectedException.class, () -> service.login("admin", "wrong3"));
        // 第 4 次即使用正确口令也被锁定拒绝
        CommandRejectedException locked = assertThrows(CommandRejectedException.class,
                () -> service.login("admin", "InitialPass123"));
        assertTrue(locked.getReason().contains("失败次数过多"));
    }
}
