// 守护纪律：每日兜底三件事——超时 processing 判定重投（崩溃续跑）、转录尾部补投幂等
// （扫描批次标识撞 uk 拒插）、done 行超期清理（拨时钟 30 天）（contracts/sediment-pipeline.md §2）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.fixtures.FixedClock;
import cn.edu.scut.chatbot.memory.sediment.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SedimentFallbackScannerTest {

    private SedimentJobMapper jobMapper;
    private SedimentTranscriptMapper transcriptMapper;
    private SedimentFallbackScanner scanner;
    private FixedClock clock;

    @BeforeEach
    void setUp() {
        jobMapper = mock(SedimentJobMapper.class);
        transcriptMapper = mock(SedimentTranscriptMapper.class);
        SystemParamService systemParams = mock(SystemParamService.class);
        when(systemParams.getString(anyString(), any())).thenReturn(null);
        clock = new FixedClock();
        scanner = new SedimentFallbackScanner(jobMapper, transcriptMapper,
                systemParams, clock);
    }

    private static SedimentGroupDO group(String groupId) {
        SedimentGroupDO group = new SedimentGroupDO();
        group.setPlatform("qq");
        group.setBotId("10000");
        group.setGroupId(groupId);
        return group;
    }

    private static SedimentTranscriptDO tailEntry(long id) {
        SedimentTranscriptDO entry = new SedimentTranscriptDO();
        entry.setId(id);
        entry.setKind("user");
        entry.setOccurredAt(LocalDateTime.of(2026, 11, 20, 12, 0));
        entry.setPayload("{}");
        return entry;
    }

    @Test
    void 超时processing判定重投_心跳30分钟前_活着的不动() {
        SedimentJobDO stale = new SedimentJobDO();
        stale.setId(5L);
        stale.setStatus(SedimentJobDO.STATUS_PROCESSING);
        when(jobMapper.selectStaleProcessing(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(stale));
        when(jobMapper.resetStaleProcessing(eq(5L), any(LocalDateTime.class))).thenReturn(1);
        when(transcriptMapper.selectDistinctGroups()).thenReturn(List.of());

        int reset = scanner.resetStaleProcessing(LocalDateTime.now(clock));

        assertThat(reset).isEqualTo(1);
        ArgumentCaptor<LocalDateTime> timeoutBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobMapper).resetStaleProcessing(eq(5L), timeoutBefore.capture());
        assertThat(timeoutBefore.getValue())
                .isCloseTo(LocalDateTime.now(clock).minusMinutes(30), within(60));
    }

    @Test
    void 转录尾部补投_无工单覆盖的区间落daily_scan工单_幂等键为扫描批次() {
        when(transcriptMapper.selectDistinctGroups()).thenReturn(List.of(group("g1")));
        when(jobMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of());   // 无任何工单覆盖
        when(transcriptMapper.selectMaxEntryId(anyString(), anyString(), anyString()))
                .thenReturn(300L);
        when(transcriptMapper.selectAfterId(anyString(), anyString(), anyString(),
                eq(0L), anyInt())).thenReturn(List.of(tailEntry(250L), tailEntry(300L)));

        int supplemented = scanner.supplementUncoveredTail(LocalDateTime.now(clock));

        assertThat(supplemented).isEqualTo(1);
        ArgumentCaptor<SedimentJobDO> job = ArgumentCaptor.forClass(SedimentJobDO.class);
        verify(jobMapper).insertJob(job.capture());
        assertThat(job.getValue().getTriggerKind()).isEqualTo(SedimentJobDO.KIND_DAILY_SCAN);
        assertThat(job.getValue().getIdempotentKey())
                .isEqualTo("scan-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                        .format(java.time.LocalDate.now(clock)));
        assertThat(job.getValue().getPayload())
                .contains("\"firstEntryId\":250").contains("\"lastEntryId\":300");
    }

    @Test
    void 尾部已被工单覆盖_不补投() {
        when(transcriptMapper.selectDistinctGroups()).thenReturn(List.of(group("g1")));
        SedimentJobDO covered = new SedimentJobDO();
        covered.setPayload("{\"summaryEntryId\":9,\"firstEntryId\":100,\"lastEntryId\":300}");
        when(jobMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of(covered));
        when(transcriptMapper.selectMaxEntryId(anyString(), anyString(), anyString()))
                .thenReturn(300L);

        int supplemented = scanner.supplementUncoveredTail(LocalDateTime.now(clock));

        assertThat(supplemented).isZero();
        verify(jobMapper, never()).insertJob(any(SedimentJobDO.class));
    }

    @Test
    void 补投幂等_同批次二次落单被唯一索引拒插_静默吞() {
        when(transcriptMapper.selectDistinctGroups()).thenReturn(List.of(group("g1")));
        when(jobMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(transcriptMapper.selectMaxEntryId(anyString(), anyString(), anyString()))
                .thenReturn(300L);
        when(transcriptMapper.selectAfterId(anyString(), anyString(), anyString(),
                anyLong(), anyInt())).thenReturn(List.of(tailEntry(250L)));
        when(jobMapper.insertJob(any(SedimentJobDO.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_idem"));

        assertThatCode(() -> scanner.supplementUncoveredTail(LocalDateTime.now(clock)))
                .doesNotThrowAnyException();
    }

    @Test
    void done行清理_拨时钟30天_超期行删除() {
        when(transcriptMapper.selectDistinctGroups()).thenReturn(List.of());
        when(jobMapper.deleteDoneBefore(any(LocalDateTime.class), anyInt())).thenReturn(7);

        int purged = scanner.cleanupDoneRows(LocalDateTime.now(clock));

        assertThat(purged).isEqualTo(7);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobMapper).deleteDoneBefore(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue())
                .isCloseTo(LocalDateTime.now(clock).minusDays(30), within(60));
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long seconds) {
        return org.assertj.core.api.Assertions.within(
                seconds, java.time.temporal.ChronoUnit.SECONDS);
    }
}
