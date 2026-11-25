// 守护纪律：强化单次上限与连续命中、归档判定与恢复、归档 180 天物理清理（拨时钟）、
// 软上限加速且无硬删（负向断言）、取代链互链与撤销、修正触发重嵌入
// （contracts/sediment-pipeline.md §5；quickstart 场景 A 生命周期段）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.fixtures.FixedClock;
import cn.edu.scut.chatbot.memory.entry.MemoryDecay;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.entry.MemoryLifecycleServiceImpl;
import cn.edu.scut.chatbot.memory.profile.UserProfileMapper;
import cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryLifecycleServiceTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "80001");

    private MemoryEntryMapper entryMapper;
    private MemoryRetriever retriever;
    private UserProfileMapper profileMapper;
    private MemoryLifecycleServiceImpl service;
    private FixedClock clock;

    @BeforeEach
    void setUp() {
        entryMapper = mock(MemoryEntryMapper.class);
        retriever = mock(MemoryRetriever.class);
        profileMapper = mock(UserProfileMapper.class);
        SystemParamService systemParams = mock(SystemParamService.class);
        when(systemParams.getString(eq("memory.decay.strength"), any())).thenReturn(null);
        when(systemParams.getString(eq("memory.retrieve.params"), any())).thenReturn(null);
        clock = new FixedClock();
        service = new MemoryLifecycleServiceImpl(entryMapper, retriever, profileMapper,
                systemParams, clock);
    }

    private static MemoryEntryDO entry(long id, int strength, LocalDateTime occurred,
                                       LocalDateTime lastAccessed) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setPlatform(GROUP.platform());
        entry.setBotId(GROUP.botId());
        entry.setGroupId(GROUP.groupId());
        entry.setMemoryType(MemoryEntryDO.TYPE_FACT);
        entry.setContent("内容" + id);
        entry.setOccurredAt(occurred);
        entry.setLastAccessedAt(lastAccessed);
        entry.setStrength(strength);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        return entry;
    }

    @Test
    void 强化批量一次UPDATE_单次加5_lastAccessed统一刷新() {
        service.reinforce(List.of(1L, 2L, 3L));

        verify(entryMapper).reinforceBatch(eq(List.of(1L, 2L, 3L)), eq(5), any(LocalDateTime.class));
    }

    @Test
    void 强化空清单不调mapper_单次上限由SQL钳制() {
        service.reinforce(List.of());
        service.reinforce(null);
        verify(entryMapper, never()).reinforceBatch(anyList(), anyInt(), any());
    }

    @Test
    void 每日衰减应用有效值_低于归档阈值置archived() {
        // 昨天发生、从未访问：有效强度 50 − 1×1 = 49（高于阈值 15 → 衰减不归档）
        MemoryEntryDO fresh = entry(1L, 50, LocalDateTime.now(clock).minusDays(1), null);
        // 60 天前发生、30 天未访问：速率 4.0、跑了 30 天 → 50 − 120 → 钳 0 → 归档
        MemoryEntryDO old = entry(2L, 50, LocalDateTime.now(clock).minusDays(60),
                LocalDateTime.now(clock).minusDays(30));
        when(entryMapper.selectActiveGroups()).thenReturn(List.of(group()));
        when(entryMapper.selectActiveByGroup(GROUP.platform(), GROUP.botId(), GROUP.groupId()))
                .thenReturn(List.of(fresh, old));
        when(entryMapper.countActiveByGroup(GROUP.platform(), GROUP.botId(), GROUP.groupId()))
                .thenReturn(2L);

        service.runDailyDecayAndArchive();

        verify(entryMapper).updateStrengthAndStatus(eq(1L), eq(49),
                eq(MemoryEntryDO.STATUS_ACTIVE), any(LocalDateTime.class));
        verify(entryMapper).updateStrengthAndStatus(eq(2L), eq(0),
                eq(MemoryEntryDO.STATUS_ARCHIVED), any(LocalDateTime.class));
    }

    @Test
    void 归档满180天物理清理_拨时钟到清理界外() {
        when(entryMapper.selectActiveGroups()).thenReturn(List.of());
        service.runDailyDecayAndArchive();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(entryMapper).deleteArchivedBefore(cutoff.capture(), anyInt());
        // 清理界 = now − 180 天（archiveKeepDays 缺省）
        assertThat(cutoff.getValue())
                .isCloseTo(LocalDateTime.now(clock).minusDays(180), within(60));
    }

    @Test
    void 软上限超群_加速最低强度衰减_无硬删负向断言() {
        MemoryEntryDO low = entry(1L, 20, LocalDateTime.now(clock).minusDays(1), null);
        when(entryMapper.selectActiveGroups()).thenReturn(List.of(group()));
        when(entryMapper.selectActiveByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of(low));
        when(entryMapper.countActiveByGroup(anyString(), anyString(), anyString()))
                .thenReturn(5001L);   // 软上限 5000 超 1 条

        service.runDailyDecayAndArchive();

        // 加速后：20 − 1（自然衰减）− 3（加速） = 16 → 高于 15 不归档
        verify(entryMapper).updateStrengthAndStatus(eq(1L), eq(16),
                eq(MemoryEntryDO.STATUS_ACTIVE), any());
        // 无硬删：批任务永不调用 deleteByIds（物理删除只走归档超期清理）
        verify(entryMapper, never()).deleteByIds(anyList());
    }

    @Test
    void 软上限加速到归档_也只是置archived可恢复_不是删除() {
        MemoryEntryDO weakest = entry(1L, 10, LocalDateTime.now(clock).minusDays(1), null);
        when(entryMapper.selectActiveGroups()).thenReturn(List.of(group()));
        when(entryMapper.selectActiveByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of(weakest));
        when(entryMapper.countActiveByGroup(anyString(), anyString(), anyString()))
                .thenReturn(6000L);

        service.runDailyDecayAndArchive();

        // 10 − 1 − 3 = 6 < 15 → archived（可恢复）
        verify(entryMapper).updateStrengthAndStatus(eq(1L), eq(6),
                eq(MemoryEntryDO.STATUS_ARCHIVED), any());
        verify(entryMapper, never()).deleteByIds(anyList());
    }

    @Test
    void 取代链互链_旧条superseded指向新条_撤销回active() {
        service.supersede(11L, 22L);
        verify(entryMapper).supersede(11L, 22L);

        service.revokeSupersede(11L);
        verify(entryMapper).revokeSupersede(11L);
    }

    @Test
    void 修正正文后触发同id重嵌入() {
        when(entryMapper.selectById(1L)).thenReturn(entry(1L, 50,
                LocalDateTime.now(clock), null));

        service.reviseContent(GROUP, 1L, "修正后的正文");

        verify(entryMapper).updateContent(1L, "修正后的正文");
        verify(retriever).reembed(GROUP, 1L);
    }

    @Test
    void 修正异群条目_业务拒绝不动库() {
        MemoryEntryDO foreign = entry(1L, 50, LocalDateTime.now(clock), null);
        foreign.setGroupId("other");
        when(entryMapper.selectById(1L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.reviseContent(GROUP, 1L, "x"))
                .isInstanceOf(cn.edu.scut.chatbot.exception.CommandRejectedException.class);
        verify(entryMapper, never()).updateContent(anyLong(), anyString());
    }

    @Test
    void 删除两库同删() {
        when(entryMapper.selectById(1L)).thenReturn(entry(1L, 50,
                LocalDateTime.now(clock), null));

        service.deleteMemory(GROUP, 1L);

        verify(entryMapper).deleteByIds(List.of(1L));
        verify(retriever).deleteFromIndex(List.of(1L));
    }

    @Test
    void 个体清除_该成员记忆与画像清_同步Qdrant删除() {
        MemoryEntryDO memberEntry = entry(1L, 50, LocalDateTime.now(clock), null);
        memberEntry.setUserId("u9");
        MemoryEntryDO other = entry(2L, 50, LocalDateTime.now(clock), null);
        other.setUserId("u1");
        when(entryMapper.selectActiveByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of(memberEntry, other));

        service.purgeMemberData(GROUP, "u9");

        verify(entryMapper).deleteByIds(List.of(1L));
        verify(retriever).deleteFromIndex(List.of(1L));
        verify(profileMapper).deleteByGroupAndUser(GROUP.platform(), GROUP.botId(),
                GROUP.groupId(), "u9");
    }

    @Test
    void 衰减参数从运行层装载_坏JSON走缺省() {
        assertThat(service.decayParams()).isEqualTo(MemoryDecay.DecayParams.defaults());
    }

    private static cn.edu.scut.chatbot.memory.entry.MemoryGroupDO group() {
        cn.edu.scut.chatbot.memory.entry.MemoryGroupDO group =
                new cn.edu.scut.chatbot.memory.entry.MemoryGroupDO();
        group.setPlatform(GROUP.platform());
        group.setBotId(GROUP.botId());
        group.setGroupId(GROUP.groupId());
        return group;
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long seconds) {
        return org.assertj.core.api.Assertions.within(
                seconds, java.time.temporal.ChronoUnit.SECONDS);
    }
}
