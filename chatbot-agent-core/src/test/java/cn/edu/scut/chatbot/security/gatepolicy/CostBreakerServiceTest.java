// 守护纪律：成本熔断四层判定、次日拨钟自动恢复与当日防抖动（宪法第六条、security-gate 契约第 5 节）
package cn.edu.scut.chatbot.security.gatepolicy;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogAggregation;
import cn.edu.scut.chatbot.llm.slot.LlmCallLogMapper;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CostBreakerServiceTest {

    private LlmCallLogMapper callLogMapper;
    private SystemParamService systemParams;
    private StringRedisTemplate redis;
    private SecurityEventRecorder recorder;
    private Map<String, String> redisStore;
    private MutableClock clock;
    private CostBreakerServiceImpl service;

    private static final class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.current = initial;
            this.zone = zone;
        }

        void setInstant(Instant next) {
            this.current = next;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        callLogMapper = mock(LlmCallLogMapper.class);
        systemParams = mock(SystemParamService.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        recorder = mock(SecurityEventRecorder.class);
        redisStore = new HashMap<>();

        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.hasKey(anyString())).thenAnswer(inv -> redisStore.containsKey(inv.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null);
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        when(systemParams.getString("security.breaker.globalDailyLimit", null)).thenReturn("50.00");
        when(systemParams.getString("security.breaker.groupDailyLimit", null)).thenReturn("10.00");

        clock = new MutableClock(Instant.parse("2026-11-26T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new CostBreakerServiceImpl(callLogMapper, systemParams, redis, recorder, clock);
    }

    @Test
    void 全局与分群超阈值触发熔断并拒绝重任务与图像语音保留chatMain() {
        LlmCallLogAggregation global = new LlmCallLogAggregation();
        global.setCostAmount(new BigDecimal("60.00"));
        when(callLogMapper.selectTokenTotals(any(), any(), isNull())).thenReturn(global);

        LlmCallLogAggregation groupRow = new LlmCallLogAggregation();
        groupRow.setDimensionKey("qq:b1:g1");
        groupRow.setCostAmount(new BigDecimal("12.00"));
        when(callLogMapper.selectCostAggregation(eq("GROUP"), any(), any(), isNull()))
                .thenReturn(List.of(groupRow));

        CostBreakerService.BreakerSnapshot snap = service.aggregateDaily();
        assertTrue(snap.globalTripped());
        assertTrue(service.isTripped(CostBreakerService.SCOPE_GLOBAL));
        assertTrue(service.isTripped("qq:b1:g1"));

        // 重任务拒绝
        assertThrows(CommandRejectedException.class,
                () -> service.onHeavyTaskAttempt("qq", "b1", "g1", "u1"));
        // 图像 / 语音能力拒绝
        assertThrows(CommandRejectedException.class,
                () -> service.onAbilityCall(ModelCatalog.SLOT_IMAGE_GEN));
        assertThrows(CommandRejectedException.class,
                () -> service.onAbilityCall(ModelCatalog.SLOT_VOICE_GEN));
        // chat_main 保留被动回应（不抛异常）
        assertDoesNotThrow(() -> service.onAbilityCall(ModelCatalog.SLOT_CHAT_MAIN));

        verify(recorder, times(2)).record(eq(SecurityEventKind.CIRCUIT_BREAK), any(), any(), isNull());
    }

    @Test
    void 当日触发熔断后提高阈值不自动解除防抖动_次日拨钟自然恢复() {
        LlmCallLogAggregation global = new LlmCallLogAggregation();
        global.setCostAmount(new BigDecimal("55.00"));
        when(callLogMapper.selectTokenTotals(any(), any(), isNull())).thenReturn(global);
        when(callLogMapper.selectCostAggregation(eq("GROUP"), any(), any(), isNull())).thenReturn(List.of());

        service.aggregateDaily();
        assertTrue(service.isTripped(CostBreakerService.SCOPE_GLOBAL));

        // 同日调高阈值再跑聚合：严禁自动解除（防抖动）
        when(systemParams.getString("security.breaker.globalDailyLimit", null)).thenReturn("100.00");
        service.aggregateDaily();
        assertTrue(service.isTripped(CostBreakerService.SCOPE_GLOBAL), "当日熔断不得自动解除");

        // 拨钟到次日：新日期键自然未熔断
        clock.setInstant(Instant.parse("2026-11-27T04:00:00Z"));
        assertFalse(service.isTripped(CostBreakerService.SCOPE_GLOBAL), "次日零点后应自动恢复");
    }

    @Test
    void 手动解除立即生效并记审计事件() {
        LlmCallLogAggregation global = new LlmCallLogAggregation();
        global.setCostAmount(new BigDecimal("55.00"));
        when(callLogMapper.selectTokenTotals(any(), any(), isNull())).thenReturn(global);
        when(callLogMapper.selectCostAggregation(eq("GROUP"), any(), any(), isNull())).thenReturn(List.of());

        service.aggregateDaily();
        assertTrue(service.isTripped(CostBreakerService.SCOPE_GLOBAL));

        service.manualRelease("ops-admin");
        assertFalse(service.isTripped(CostBreakerService.SCOPE_GLOBAL));
        verify(recorder).record(eq(SecurityEventKind.CIRCUIT_BREAK), any(),
                argThat(detail -> "manualRelease".equals(detail.get("action"))
                        && "ops-admin".equals(detail.get("operator"))),
                isNull());
    }
}
