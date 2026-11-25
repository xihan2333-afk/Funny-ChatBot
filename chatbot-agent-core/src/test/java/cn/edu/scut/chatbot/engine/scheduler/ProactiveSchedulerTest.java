// 守护纪律：内部触发照常过 L0/L1 闸门不设旁路、参数化判定组合、间隔由 dedupKey 窗口拦截（T053）
package cn.edu.scut.chatbot.engine.scheduler;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.memory.social.GroupStateService;
import cn.edu.scut.chatbot.memory.social.ProactivePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * T053：AutonomyScheduler 参数化——静默 / 活跃时段 / 预算三闸与同管线登记。
 */
class ProactiveSchedulerTest {

    private GroupMapper groupMapper;
    private TriggerEventMapper triggerEventMapper;
    private EffectsQueueMapper effectsQueueMapper;
    private GroupStateService groupStateService;
    private ProactivePolicy proactivePolicy;
    private IdGenerator idGenerator;
    private MutableClock clock;
    private AutonomyScheduler scheduler;

    private final GroupKey groupKey = new GroupKey("qq", "bot1", "g1");

    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void forward(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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

    @BeforeEach
    void setUp() {
        groupMapper = mock(GroupMapper.class);
        triggerEventMapper = mock(TriggerEventMapper.class);
        effectsQueueMapper = mock(EffectsQueueMapper.class);
        groupStateService = mock(GroupStateService.class);
        proactivePolicy = mock(ProactivePolicy.class);
        idGenerator = mock(IdGenerator.class);
        when(idGenerator.nextId()).thenReturn(1L);
        clock = new MutableClock(Instant.parse("2026-11-25T14:00:00Z"));
        scheduler = new AutonomyScheduler(groupMapper, triggerEventMapper, effectsQueueMapper,
                groupStateService, proactivePolicy, idGenerator, clock);

        GroupDO group = new GroupDO();
        group.setPlatform("qq");
        group.setBotId("bot1");
        group.setGroupId("g1");
        when(groupMapper.selectAllInService()).thenReturn(List.of(group));
        when(triggerEventMapper.countInternalByTypeSince(anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(0L);
        // 默认：15 点（业务时区 UTC 下即当前时钟 14:00 → hour=14 活跃）
        when(proactivePolicy.effectiveParams(groupKey)).thenReturn(
                new ProactivePolicy.ProactiveParams(30, 2, 10, Set.of(14, 15, 20)));
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(0, 0, null, null, 0, 0));
    }

    @Test
    void 静默达三十分钟_产生破冰内部触发并走评估管线() {
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(Duration.ofMinutes(31).toMillis(), 0, null, null, 0, 0));

        scheduler.scanGroupSilence();

        ArgumentCaptor<TriggerEventDO> trigger = ArgumentCaptor.forClass(TriggerEventDO.class);
        verify(triggerEventMapper).insertTrigger(trigger.capture());
        assertEquals(TriggerEventDO.KIND_INTERNAL, trigger.getValue().getTriggerKind());
        assertEquals("silenceBreak", trigger.getValue().getEventType());
        assertEquals(TriggerEventDO.DECISION_PENDING, trigger.getValue().getDecision(),
                "内部触发不设旁路——登记 pending 走与外部触发相同的评估管线");
        ArgumentCaptor<EffectsQueueDO> effect = ArgumentCaptor.forClass(EffectsQueueDO.class);
        verify(effectsQueueMapper).enqueueMerge(effect.capture());
        assertEquals(EffectsQueueDO.KIND_ASSESSMENT, effect.getValue().getKind(),
                "破冰触发入 assessment Effect——照常过 L0/L1 闸门");
    }

    @Test
    void 静默二十九分钟_不触发() {
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(Duration.ofMinutes(29).toMillis(), 0, null, null, 0, 0));
        scheduler.scanGroupSilence();
        verify(triggerEventMapper, never()).insertTrigger(any());
    }

    @Test
    void 活跃时段外_静默再久也不触发() {
        clock.forward(Duration.ofHours(11));   // 01:00 UTC——不在活跃时段
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(Duration.ofHours(5).toMillis(), 0, null, null, 0, 0));
        scheduler.scanGroupSilence();
        verify(triggerEventMapper, never()).insertTrigger(any());
    }

    @Test
    void 当日预算耗尽_不再触发() {
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(Duration.ofMinutes(40).toMillis(), 0, null, null, 0, 0));
        when(triggerEventMapper.countInternalByTypeSince(anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(10L);
        scheduler.scanGroupSilence();
        verify(triggerEventMapper, never()).insertTrigger(any());
    }

    @Test
    void 预算尚有一条_仍可触发() {
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(Duration.ofMinutes(40).toMillis(), 0, null, null, 0, 0));
        when(triggerEventMapper.countInternalByTypeSince(anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(9L);
        scheduler.scanGroupSilence();
        verify(triggerEventMapper).insertTrigger(any());
    }

    @Test
    void 同一间隔窗口第二次登记_被uk拒插静默吞掉() {
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(Duration.ofMinutes(40).toMillis(), 0, null, null, 0, 0));
        when(triggerEventMapper.insertTrigger(any()))
                .thenReturn(1)
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_dedup"));

        scheduler.scanGroupSilence();
        clock.forward(Duration.ofMinutes(5));   // 同一 2 小时窗口内
        scheduler.scanGroupSilence();

        verify(triggerEventMapper, Mockito.times(2)).insertTrigger(any());
        verify(effectsQueueMapper, Mockito.times(1)).enqueueMerge(any());
    }

    @Test
    void dedupKey时间窗随参数间隔_两小时窗口() {
        when(groupStateService.read(groupKey)).thenReturn(
                new GroupStateService.GroupStateSnapshot(Duration.ofMinutes(40).toMillis(), 0, null, null, 0, 0));
        scheduler.scanGroupSilence();
        ArgumentCaptor<TriggerEventDO> trigger = ArgumentCaptor.forClass(TriggerEventDO.class);
        verify(triggerEventMapper).insertTrigger(trigger.capture());
        long windowMillis = 2 * 3_600_000L;
        assertTrue(trigger.getValue().getDedupKey().endsWith(
                ":" + (clock.millis() / windowMillis)), "dedupKey 窗口 = minInterval（2 小时）");
    }

    @Test
    void 群状态读取降级空快照_按不破冰处理() {
        // setUp 默认静默 0（空快照）——Redis 失败时调度器跳过本轮扫描
        scheduler.scanGroupSilence();
        verify(triggerEventMapper, never()).insertTrigger(any());
    }
}
