// 守护纪律：Qdrant 是可全量重建的派生索引（MySQL 是事实源）、payload 只放稳定过滤字段
// （strength 等易变字段变更后 payload 不变——负向断言）、重建走 id 游标全量重灌
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.SlotResolver;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.ModelCapabilities;
import cn.edu.scut.chatbot.loop.ModelConfig;
import cn.edu.scut.chatbot.loop.ModelRef;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever;
import cn.edu.scut.chatbot.memory.retrieve.MemoryRetrieverImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryRebuildTest {

    private MemoryEntryMapper entryMapper;
    private QdrantClient qdrant;
    private EmbeddingClient embeddingClient;
    private SlotResolver slotResolver;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        entryMapper = mock(MemoryEntryMapper.class);
        qdrant = mock(QdrantClient.class);
        embeddingClient = mock(EmbeddingClient.class);
        slotResolver = mock(SlotResolver.class);
        SystemParamService systemParams = mock(SystemParamService.class);
        retriever = new MemoryRetrieverImpl(entryMapper, qdrant, embeddingClient,
                slotResolver, systemParams, Clock.systemUTC());

        ModelRef modelRef = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", "embedding-3",
                "http://upstream", new ModelCapabilities(false, false, 128000, 4096), null, "embedding");
        when(slotResolver.resolve(ModelCatalog.SLOT_EMBEDDING))
                .thenReturn(new ModelConfig(modelRef, GenerationParams.defaults()));
        when(embeddingClient.embed(eq(ModelCatalog.SLOT_EMBEDDING), any(), anyList()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(2);
                    return texts.stream().map(text -> new float[8]).toList();
                });
    }

    private static MemoryEntryDO entry(long id, String content, int strength) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setPlatform("qq");
        entry.setBotId("10000");
        entry.setGroupId("80001");
        entry.setMemoryType(MemoryEntryDO.TYPE_FACT);
        entry.setContent(content);
        entry.setOccurredAt(LocalDateTime.of(2026, 11, 20, 12, 0));
        entry.setSedimentAt(LocalDateTime.of(2026, 11, 21, 8, 0));
        entry.setStrength(strength);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        return entry;
    }

    @Test
    void 重建从MySQL全量active重灌_目标集合不存在则按探测维度创建() {
        when(qdrant.describeCollection("memory_entry")).thenReturn(null);
        when(entryMapper.selectActiveAfterId(0L, 100))
                .thenReturn(List.of(entry(1, "李四是程序员", 50), entry(2, "群里在聊装修", 60)));
        when(entryMapper.selectActiveAfterId(2L, 100)).thenReturn(List.of());

        MemoryRetriever.RebuildReport report = retriever.rebuildCollection("memory_entry", false);

        verify(qdrant).createCollection(eq("memory_entry"), eq(8),
                eq(MemoryIndexPoints.STABLE_PAYLOAD_INDEXES));
        ArgumentCaptor<List<QdrantClient.QdrantPoint>> points = pointsCaptor();
        verify(qdrant).upsert(eq("memory_entry"), points.capture());
        assertThat(points.getValue()).hasSize(2);
        assertThat(report.totalEntries()).isEqualTo(2);
        assertThat(report.aliasSwitched()).isFalse();
    }

    @Test
    void 重建分批游标推进_两批全部灌入() {
        when(qdrant.describeCollection("memory_entry"))
                .thenReturn(new QdrantClient.QdrantCollectionInfo(8, 0, "green"));
        when(entryMapper.selectActiveAfterId(0L, 100))
                .thenReturn(List.of(entry(1, "a", 50)));
        when(entryMapper.selectActiveAfterId(1L, 100))
                .thenReturn(List.of(entry(2, "b", 60)));
        when(entryMapper.selectActiveAfterId(2L, 100)).thenReturn(List.of());

        retriever.rebuildCollection("memory_entry", false);

        verify(qdrant, times(2)).upsert(eq("memory_entry"), pointsCaptor().capture());
    }

    @Test
    void switchAliasAfter为真时灌完切别名_零停机迁移语义() {
        when(qdrant.describeCollection("memory_entry_v2")).thenReturn(null);
        when(entryMapper.selectActiveAfterId(0L, 100)).thenReturn(List.of());

        retriever.rebuildCollection("memory_entry_v2", true);

        verify(qdrant).switchAlias(MemoryIndexPoints.COLLECTION_ALIAS, "memory_entry_v2");
    }

    @Test
    void 目标集合维度与槽位不符_阻断() {
        when(qdrant.describeCollection("memory_entry"))
                .thenReturn(new QdrantClient.QdrantCollectionInfo(1024, 0, "green"));

        assertThatThrownBy(() -> retriever.rebuildCollection("memory_entry", false))
                .isInstanceOf(cn.edu.scut.chatbot.exception.NonRetryableException.class)
                .hasMessageContaining("1024");
        verify(qdrant, never()).upsert(any(), anyList());
    }

    @Test
    void payload只含稳定过滤字段_strength变更后payload不变_易变字段永不进索引() {
        // 同一条目两次重建：中间把 strength 从 50 改到 90、补了 lastAccessedAt——
        // 两次 upsert 的 payload 必须逐字段一致（负向断言：索引层无易变字段）
        MemoryEntryDO before = entry(1, "李四是程序员", 50);
        MemoryEntryDO after = entry(1, "李四是程序员", 90);
        after.setLastAccessedAt(LocalDateTime.of(2026, 11, 25, 9, 0));

        Map<String, Object> payloadBefore = MemoryIndexPoints.stablePayload(before, Clock.systemUTC().getZone());
        Map<String, Object> payloadAfter = MemoryIndexPoints.stablePayload(after, Clock.systemUTC().getZone());

        assertThat(payloadBefore).isEqualTo(payloadAfter);
        assertThat(payloadBefore).containsKeys("platform", "botId", "groupId", "memoryType", "occurredAt");
        assertThat(payloadBefore).doesNotContainKeys("strength", "lastAccessedAt", "status", "topicLifecycle");
    }

    @Test
    void 记忆修正重嵌入_同id同payload重新upsert() {
        when(entryMapper.selectById(1L)).thenReturn(entry(1, "李四辞职了（修正后）", 50));

        retriever.reembed(new cn.edu.scut.chatbot.bootstrap.GroupKey("qq", "10000", "80001"), 1L);

        ArgumentCaptor<List<QdrantClient.QdrantPoint>> points = pointsCaptor();
        verify(qdrant).upsert(eq(MemoryIndexPoints.COLLECTION_ALIAS), points.capture());
        assertThat(points.getValue()).hasSize(1);
        assertThat(points.getValue().get(0).id()).isEqualTo(1L);
    }

    @Test
    void 索引删除按点id清单_空清单不调上游() {
        retriever.deleteFromIndex(List.of());
        verify(qdrant, never()).delete(any(), anyList());

        retriever.deleteFromIndex(List.of(1L, 2L));
        verify(qdrant).delete(MemoryIndexPoints.COLLECTION_ALIAS, List.of(1L, 2L));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<QdrantClient.QdrantPoint>> pointsCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
