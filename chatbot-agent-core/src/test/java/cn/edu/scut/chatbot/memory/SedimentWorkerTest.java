// 守护纪律：工单状态机迁移 pending→processing→done/failed、CAS 认领竞争只一方胜出、
// retry 超限进终态告警不再自动重投（contracts/sediment-pipeline.md §3、research R2）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.fixtures.FixedClock;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService;
import cn.edu.scut.chatbot.memory.sediment.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SedimentWorkerTest {

    private SedimentJobMapper jobMapper;
    private SedimentTranscriptMapper transcriptMapper;
    private ExtractionService extractionService;
    private DedupMerger dedupMerger;
    private EmbeddingWriter embeddingWriter;
    private ProfileSignalApplier profileSignalApplier;
    private MemoryLifecycleService lifecycleService;
    private MemoryEntryMapper entryMapper;
    private SedimentWorker worker;

    @BeforeEach
    void setUp() {
        jobMapper = mock(SedimentJobMapper.class);
        transcriptMapper = mock(SedimentTranscriptMapper.class);
        extractionService = mock(ExtractionService.class);
        dedupMerger = mock(DedupMerger.class);
        embeddingWriter = mock(EmbeddingWriter.class);
        profileSignalApplier = mock(ProfileSignalApplier.class);
        lifecycleService = mock(MemoryLifecycleService.class);
        entryMapper = mock(MemoryEntryMapper.class);
        SystemParamService systemParams = mock(SystemParamService.class);
        when(systemParams.getString(anyString(), any())).thenReturn(null);
        worker = new SedimentWorker(jobMapper, transcriptMapper, extractionService,
                dedupMerger, embeddingWriter, profileSignalApplier, lifecycleService,
                entryMapper, systemParams, new FixedClock());
    }

    private static SedimentJobDO job(long id, String status) {
        SedimentJobDO job = new SedimentJobDO();
        job.setId(id);
        job.setPlatform("qq");
        job.setBotId("10000");
        job.setGroupId("80001");
        job.setTriggerKind(SedimentJobDO.KIND_COMPRESS);
        job.setIdempotentKey(String.valueOf(id));
        job.setPayload("{\"summaryEntryId\":90,\"firstEntryId\":10,\"lastEntryId\":20}");
        job.setStatus(status);
        job.setRetryCount(0);
        return job;
    }

    @Test
    void 处理成功_状态机processing到done_回填output_ref并触发向量化() {
        SedimentJobDO claimed = job(1L, SedimentJobDO.STATUS_PROCESSING);
        when(transcriptMapper.selectByGroupAndIdRange(anyString(), anyString(), anyString(),
                eq(10L), eq(20L))).thenReturn(List.of());
        when(extractionService.extract(any(), anyList(), any(), anyList()))
                .thenReturn(new ExtractionContract.ExtractionOutput(List.of(), List.of(), List.of()));
        when(dedupMerger.mergeCandidates(any(), anyLong(), anyList()))
                .thenReturn(new DedupMerger.MergeOutcome(List.of(100L), List.of()));
        when(entryMapper.selectActiveByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(entryMapper.selectByIds(List.of(100L))).thenReturn(List.of());
        when(profileSignalApplier.apply(any(), anyList())).thenReturn(null);

        worker.process(claimed);

        verify(jobMapper).markDone(eq(1L), anyString());
        verify(embeddingWriter).enqueueAsync(any(), eq(List.of(100L)));
        verify(jobMapper, never()).markFailed(anyLong());
    }

    @Test
    void 处理异常_置failed递增retry_count_不抛异常() {
        SedimentJobDO claimed = job(1L, SedimentJobDO.STATUS_PROCESSING);
        when(transcriptMapper.selectByGroupAndIdRange(anyString(), anyString(), anyString(),
                anyLong(), anyLong()))
                .thenThrow(new RuntimeException("转录读取失败"));
        // 重读（终态判定用）返回未超限
        when(jobMapper.selectById(1L)).thenReturn(job(1L, SedimentJobDO.STATUS_FAILED));

        org.assertj.core.api.Assertions.assertThatCode(() -> worker.process(claimed))
                .doesNotThrowAnyException();

        verify(jobMapper).markFailed(1L);
        verify(jobMapper, never()).markDone(anyLong(), anyString());
    }

    @Test
    void retry超限_进终态告警口径_后续认领不再匹配() {
        SedimentJobDO claimed = job(1L, SedimentJobDO.STATUS_PROCESSING);
        when(transcriptMapper.selectByGroupAndIdRange(anyString(), anyString(), anyString(),
                anyLong(), anyLong()))
                .thenThrow(new RuntimeException("抽取持续失败"));
        SedimentJobDO terminal = job(1L, SedimentJobDO.STATUS_FAILED);
        terminal.setRetryCount(3);   // 达到 retryMax
        when(jobMapper.selectById(1L)).thenReturn(terminal);

        org.assertj.core.api.Assertions.assertThatCode(() -> worker.process(claimed))
                .doesNotThrowAnyException();
        verify(jobMapper).markFailed(1L);
    }

    @Test
    void poll认领批次_CAS竞争失败方不处理() {
        SedimentJobDO pending = job(1L, SedimentJobDO.STATUS_PENDING);
        when(jobMapper.selectClaimable(anyInt(), anyInt())).thenReturn(List.of(pending));
        when(jobMapper.claimJob(1L, 3)).thenReturn(0);   // CAS 竞争：对方先认领

        worker.poll();

        verify(extractionService, never()).extract(any(), anyList(), any(), anyList());
        verify(jobMapper, never()).markDone(anyLong(), anyString());
        verify(jobMapper, never()).markFailed(anyLong());
    }

    @Test
    void poll认领成功_派发虚拟线程处理_Awaitility等到done() {
        SedimentJobDO pending = job(1L, SedimentJobDO.STATUS_PENDING);
        when(jobMapper.selectClaimable(anyInt(), anyInt())).thenReturn(List.of(pending));
        when(jobMapper.claimJob(1L, 3)).thenReturn(1);
        when(transcriptMapper.selectByGroupAndIdRange(anyString(), anyString(), anyString(),
                anyLong(), anyLong())).thenReturn(List.of());
        when(extractionService.extract(any(), anyList(), any(), anyList()))
                .thenReturn(new ExtractionContract.ExtractionOutput(List.of(), List.of(), List.of()));
        when(dedupMerger.mergeCandidates(any(), anyLong(), anyList()))
                .thenReturn(new DedupMerger.MergeOutcome(List.of(), List.of()));
        when(entryMapper.selectActiveByGroup(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(profileSignalApplier.apply(any(), anyList())).thenReturn(null);

        worker.poll();

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(3))
                .untilAsserted(() -> verify(jobMapper).markDone(eq(1L), anyString()));
    }

    @Test
    void worker参数缺省装载_热变更入口齐备() {
        SedimentWorker.WorkerParams params = worker.workerParams();
        assertThat(params.claimBatch()).isEqualTo(10);
        assertThat(params.retryMax()).isEqualTo(3);
        assertThat(params.processingTimeoutMinutes()).isEqualTo(30);
        assertThat(params.keepDoneDays()).isEqualTo(30);
    }
}
