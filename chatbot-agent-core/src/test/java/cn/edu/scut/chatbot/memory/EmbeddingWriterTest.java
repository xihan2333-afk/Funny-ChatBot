// 守护纪律：双存储次序 MySQL 先落 Qdrant 后写；写失败重试超限「在库不在索引」可接受降级
// 不抛异常；embedding 槽位未绑定跳过告警条目留库待补嵌（contracts/sediment-pipeline.md §3）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.sediment.EmbeddingWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmbeddingWriterTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "80001");

    private MemoryEntryMapper entryMapper;
    private EmbeddingClient embeddingClient;
    private QdrantClient qdrant;
    private EmbeddingWriter writer;

    @BeforeEach
    void setUp() {
        entryMapper = mock(MemoryEntryMapper.class);
        embeddingClient = mock(EmbeddingClient.class);
        qdrant = mock(QdrantClient.class);
        writer = new EmbeddingWriter(entryMapper, embeddingClient, qdrant, Clock.systemUTC());
    }

    private static MemoryEntryDO entry(long id) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setPlatform(GROUP.platform());
        entry.setBotId(GROUP.botId());
        entry.setGroupId(GROUP.groupId());
        entry.setMemoryType(MemoryEntryDO.TYPE_FACT);
        entry.setContent("内容" + id);
        entry.setOccurredAt(LocalDateTime.of(2026, 11, 20, 12, 0));
        entry.setStrength(50);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        return entry;
    }

    @Test
    void 落库条目批量向量化并upsert() {
        when(entryMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(entry(1L), entry(2L)));
        when(embeddingClient.embed(anyString(), any(), anyList()))
                .thenReturn(List.of(new float[8], new float[8]));

        writer.write(GROUP, List.of(1L, 2L));

        ArgumentCaptor<List<QdrantClient.QdrantPoint>> points = pointsCaptor();
        verify(qdrant).upsert(anyString(), points.capture());
        assertThat(points.getValue()).hasSize(2);
        assertThat(points.getValue().get(0).id()).isEqualTo(1L);
        assertThat(points.getValue().get(1).id()).isEqualTo(2L);
    }

    @Test
    void embedding槽位未绑定_跳过向量化_条目留库待补嵌() {
        when(entryMapper.selectByIds(List.of(1L))).thenReturn(List.of(entry(1L)));
        when(embeddingClient.embed(anyString(), any(), anyList()))
                .thenThrow(new cn.edu.scut.chatbot.llm.capability.OpenAiCompatCapabilityClient
                        .CapabilityException("llm.config_missing", "槽位未绑定"));

        assertThatCode(() -> writer.write(GROUP, List.of(1L)))
                .doesNotThrowAnyException();
        verify(qdrant, never()).upsert(anyString(), anyList());
        verify(entryMapper, never()).deleteByIds(anyList());   // 条目留库（不删）
    }

    @Test
    void qdrant写失败重试三次后降级_在库不在索引不抛异常() {
        when(entryMapper.selectByIds(List.of(1L))).thenReturn(List.of(entry(1L)));
        when(embeddingClient.embed(anyString(), any(), anyList()))
                .thenReturn(List.of(new float[8]));
        org.mockito.Mockito.doThrow(new cn.edu.scut.chatbot.exception.RetryableException(
                        "memory.qdrant.unavailable", "上游错误"))
                .when(qdrant).upsert(anyString(), anyList());

        assertThatCode(() -> writer.write(GROUP, List.of(1L)))
                .doesNotThrowAnyException();
        // 三次重试后告警降级（MySQL 条目保留）
        verify(qdrant, times(3)).upsert(anyString(), anyList());
    }

    @Test
    void 异步入口最终落upsert_Awaitility等待可观察条件() {
        when(entryMapper.selectByIds(List.of(1L))).thenReturn(List.of(entry(1L)));
        when(embeddingClient.embed(anyString(), any(), anyList()))
                .thenReturn(List.of(new float[8]));

        writer.enqueueAsync(GROUP, List.of(1L));

        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(3))
                .untilAsserted(() -> verify(qdrant).upsert(anyString(), anyList()));
    }

    @Test
    void 归档与异群条目不进索引批次() {
        MemoryEntryDO archived = entry(1L);
        archived.setStatus(MemoryEntryDO.STATUS_ARCHIVED);
        MemoryEntryDO foreign = entry(2L);
        foreign.setGroupId("other-group");
        when(entryMapper.selectByIds(anyList())).thenReturn(List.of(archived, foreign));

        writer.write(GROUP, List.of(1L, 2L));

        verify(qdrant, never()).upsert(anyString(), anyList());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<QdrantClient.QdrantPoint>> pointsCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
