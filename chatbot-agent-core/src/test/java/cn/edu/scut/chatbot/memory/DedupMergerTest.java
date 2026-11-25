// 守护纪律：内容级幂等——相似度边界 0.84/0.85/0.86 判合并、合并、新建；
// 合并强化 +10 与 mergedContent 正文扩充；embedding 不可用降级全新建（契约 §3 两级幂等）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.fixtures.DeterministicEmbeddings;
import cn.edu.scut.chatbot.fixtures.SeededIdGenerator;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.sediment.DedupMerger;
import cn.edu.scut.chatbot.memory.sediment.ExtractionContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DedupMergerTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "80001");

    private MemoryEntryMapper entryMapper;
    private EmbeddingClient embeddingClient;
    private QdrantClient qdrant;
    private DedupMerger merger;

    @BeforeEach
    void setUp() {
        entryMapper = mock(MemoryEntryMapper.class);
        embeddingClient = mock(EmbeddingClient.class);
        qdrant = mock(QdrantClient.class);
        SystemParamService systemParams = mock(SystemParamService.class);
        when(systemParams.getString(eq("memory.retrieve.params"), any())).thenReturn(null);
        merger = new DedupMerger(entryMapper, embeddingClient, qdrant,
                new SeededIdGenerator(), Clock.systemUTC(), systemParams);
    }

    /**
     * 构造既有条目（活跃、同群），返回其基准向量
     */
    private float[] seedExisting(long id, String content) {
        MemoryEntryDO existing = new MemoryEntryDO();
        existing.setId(id);
        existing.setPlatform(GROUP.platform());
        existing.setBotId(GROUP.botId());
        existing.setGroupId(GROUP.groupId());
        existing.setMemoryType(MemoryEntryDO.TYPE_FACT);
        existing.setContent(content);
        existing.setStrength(50);
        existing.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        when(entryMapper.selectById(id)).thenReturn(existing);
        return DeterministicEmbeddings.vectorOf(content, 8);
    }

    /**
     * 桩：embedding 返回候选文本的哈希向量；Qdrant 检索返回指定 id + 相似度的单条命中
     */
    private void stubSearch(float[] candidateVector, long hitId, double similarity) {
        when(embeddingClient.embed(anyString(), any(), anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(2);
            return texts.stream().map(text -> DeterministicEmbeddings.vectorOf(text, 8)).toList();
        });
        when(qdrant.search(anyString(), any(float[].class), any(), anyInt()))
                .thenReturn(List.of(new QdrantClient.QdrantHit(hitId, similarity, java.util.Map.of())));
    }

    @Test
    void 相似度低于阈值0点84_新建条目() {
        float[] base = seedExisting(1L, "李四是程序员");
        stubSearch(base, 1L, 0.84);

        DedupMerger.MergeOutcome outcome = merger.mergeCandidates(GROUP, 77L,
                List.of(candidate("李四在做前端", null)));

        assertThat(outcome.mergedIds()).isEmpty();
        assertThat(outcome.createdIds()).hasSize(1);
        verify(entryMapper).insertEntry(any(MemoryEntryDO.class));
        verify(entryMapper, never()).updateStrengthAndStatus(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void 相似度等于阈值0点85_判合并() {
        float[] base = seedExisting(1L, "李四是程序员");
        stubSearch(base, 1L, 0.85);

        DedupMerger.MergeOutcome outcome = merger.mergeCandidates(GROUP, 77L,
                List.of(candidate("李四是个程序员", null)));

        assertThat(outcome.createdIds()).isEmpty();
        assertThat(outcome.mergedIds()).containsExactly(1L);
        verify(entryMapper, never()).insertEntry(any(MemoryEntryDO.class));
    }

    @Test
    void 相似度高于阈值0点86_判合并() {
        seedExisting(1L, "李四是程序员");
        stubSearch(null, 1L, 0.86);

        DedupMerger.MergeOutcome outcome = merger.mergeCandidates(GROUP, 77L,
                List.of(candidate("李四是程序员啊", null)));

        assertThat(outcome.mergedIds()).containsExactly(1L);
    }

    @Test
    void 合并强化10点_SQL钳100_mergedContent正文重写() {
        seedExisting(1L, "李四是程序员");
        stubSearch(null, 1L, 0.9);

        MemoryEntryDO existing = entryMapper.selectById(1L);
        existing.setStrength(95);
        when(entryMapper.selectById(1L)).thenReturn(existing);

        merger.mergeCandidates(GROUP, 77L,
                List.of(candidate("李四是个程序员",
                        "李四是程序员，2026 年在甲方做后端（合并后正文）")));

        // 强化 +10 钳到 100
        verify(entryMapper).updateStrengthAndStatus(eq(1L), eq(100),
                eq(MemoryEntryDO.STATUS_ACTIVE), any());
        // mergedContent 拼接重写
        verify(entryMapper).updateContent(1L, "李四是程序员，2026 年在甲方做后端（合并后正文）");
    }

    @Test
    void 合并未给mergedContent_只强化不改正文() {
        seedExisting(1L, "李四是程序员");
        stubSearch(null, 1L, 0.9);

        merger.mergeCandidates(GROUP, 77L, List.of(candidate("李四是个程序员", null)));

        verify(entryMapper).updateStrengthAndStatus(eq(1L), eq(60),
                eq(MemoryEntryDO.STATUS_ACTIVE), any());
        verify(entryMapper, never()).updateContent(anyLong(), anyString());
    }

    @Test
    void 新建条目字段齐备_source回指工单_topic默认hot带keywords() {
        stubSearch(null, -1L, 0.0);
        when(qdrant.search(anyString(), any(float[].class), any(), anyInt()))
                .thenReturn(List.of());

        ArgumentCaptor<MemoryEntryDO> inserted = ArgumentCaptor.forClass(MemoryEntryDO.class);
        merger.mergeCandidates(GROUP, 77L, List.of(new ExtractionContract.CandidateEntry(
                "topic", "群里在聊装修", null, "2026-11-20", List.of("预算", "瓷砖"), null)));

        verify(entryMapper).insertEntry(inserted.capture());
        MemoryEntryDO entry = inserted.getValue();
        assertThat(entry.getMemoryType()).isEqualTo("topic");
        assertThat(entry.getContent()).isEqualTo("群里在聊装修");
        assertThat(entry.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 0, 0));
        assertThat(entry.getStrength()).isEqualTo(50);
        assertThat(entry.getStatus()).isEqualTo(MemoryEntryDO.STATUS_ACTIVE);
        assertThat(entry.getTopicLifecycle()).isEqualTo(MemoryEntryDO.TOPIC_HOT);
        assertThat(entry.getKeywords()).contains("预算").contains("瓷砖");
        assertThat(entry.getSourceRef()).contains("77");
        // 雪花发放先于落库
        assertThat(entry.getId()).isPositive();
    }

    @Test
    void 向量化不可用_降级全新建_不杀批() {
        when(embeddingClient.embed(anyString(), any(), anyList()))
                .thenThrow(new RuntimeException("embedding 槽位未绑定"));

        DedupMerger.MergeOutcome outcome = merger.mergeCandidates(GROUP, 77L,
                List.of(candidate("李四是程序员", null), candidate("张三喜欢原神", null)));

        assertThat(outcome.createdIds()).hasSize(2);
        assertThat(outcome.mergedIds()).isEmpty();
    }

    @Test
    void 近邻检索失败_按新建处理_索引层可失败事实源在MySQL() {
        when(embeddingClient.embed(anyString(), any(), anyList()))
                .thenReturn(List.of(DeterministicEmbeddings.vectorOf("李四是程序员", 8)));
        when(qdrant.search(anyString(), any(float[].class), any(), anyInt()))
                .thenThrow(new RuntimeException("Qdrant 不可用"));

        DedupMerger.MergeOutcome outcome = merger.mergeCandidates(GROUP, 77L,
                List.of(candidate("李四是程序员", null)));

        assertThat(outcome.createdIds()).hasSize(1);
    }

    private static ExtractionContract.CandidateEntry candidate(String content, String merged) {
        return new ExtractionContract.CandidateEntry("fact", content, null,
                "2026-11-20", List.of(), merged);
    }
}
