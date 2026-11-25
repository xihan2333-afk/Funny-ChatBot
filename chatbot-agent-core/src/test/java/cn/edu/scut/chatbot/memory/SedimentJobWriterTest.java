// 守护纪律：压缩成功必有工单、幂等键重复投递只插一行（uk_idem 拒插静默吞——
// 两级幂等的工单级，contracts/sediment-pipeline.md §1）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobDO;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobMapper;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobWriter;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobWriterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SedimentJobWriterTest {

    private SedimentJobMapper jobMapper;
    private SedimentJobWriter writer;
    private GroupKey group;

    @BeforeEach
    void setUp() {
        jobMapper = mock(SedimentJobMapper.class);
        writer = new SedimentJobWriterImpl(jobMapper);
        group = new GroupKey("qq", "10000", "80001");
    }

    @Test
    void 压缩事务内落单_幂等键取summaryEntryId_payload带区间与摘要() {
        writer.submitOnCompaction(group, 9527L,
                new SedimentJobWriter.TranscriptRange(100L, 200L));

        ArgumentCaptor<SedimentJobDO> job = ArgumentCaptor.forClass(SedimentJobDO.class);
        verify(jobMapper).insertJob(job.capture());
        assertThat(job.getValue().getTriggerKind()).isEqualTo(SedimentJobDO.KIND_COMPRESS);
        assertThat(job.getValue().getIdempotentKey()).isEqualTo("9527");
        assertThat(job.getValue().getStatus()).isEqualTo(SedimentJobDO.STATUS_PENDING);
        assertThat(job.getValue().getRetryCount()).isZero();
        assertThat(job.getValue().getPayload())
                .contains("\"summaryEntryId\":9527")
                .contains("\"firstEntryId\":100")
                .contains("\"lastEntryId\":200");
        assertThat(job.getValue().getPlatform()).isEqualTo("qq");
        assertThat(job.getValue().getGroupId()).isEqualTo("80001");
    }

    @Test
    void 幂等键重复投递_唯一索引拒插静默吞不抛异常() {
        when(jobMapper.insertJob(any(SedimentJobDO.class)))
                .thenThrow(new DuplicateKeyException("uk_idem"));

        assertThatCode(() -> writer.submitOnCompaction(group, 9527L,
                new SedimentJobWriter.TranscriptRange(100L, 200L)))
                .doesNotThrowAnyException();
        verify(jobMapper, times(1)).insertJob(any(SedimentJobDO.class));
    }
}
