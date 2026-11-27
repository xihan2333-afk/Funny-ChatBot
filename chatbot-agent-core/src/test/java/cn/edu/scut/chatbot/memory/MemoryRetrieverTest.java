// 守护纪律：检索六步管道（隔离三元组过滤强制注入、Qdrant 只管召回业务打分在应用层、
// 命中进返回结果才强化、token 预算硬上限、结果整体定界包裹、失败 isError 不杀循环）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.context.window.TokenEstimator;
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
import cn.edu.scut.chatbot.security.delimit.DelimitHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryRetrieverTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "80001");

    /**
     * 固定时钟 2026-11-25 12:00（Asia/Shanghai 口径的 UTC Instant）——衰减因子可精确推算
     */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-25T04:00:00Z"), ZoneOffset.UTC);

    private MemoryEntryMapper entryMapper;
    private QdrantClient qdrant;
    private SystemParamService systemParams;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        entryMapper = mock(MemoryEntryMapper.class);
        qdrant = mock(QdrantClient.class);
        systemParams = mock(SystemParamService.class);
        SlotResolver slotResolver = mock(SlotResolver.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        retriever = new MemoryRetrieverImpl(entryMapper, qdrant, embeddingClient,
                slotResolver, systemParams, CLOCK);

        when(systemParams.getString(anyString(), any())).thenReturn(null);
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

    private static MemoryEntryDO entry(long id, String content, int strength,
                                       LocalDateTime occurredAt, LocalDateTime lastAccessedAt) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setPlatform("qq");
        entry.setBotId("10000");
        entry.setGroupId("80001");
        entry.setMemoryType(MemoryEntryDO.TYPE_FACT);
        entry.setContent(content);
        entry.setOccurredAt(occurredAt);
        entry.setSedimentAt(occurredAt.plusDays(1));
        entry.setStrength(strength);
        entry.setLastAccessedAt(lastAccessedAt);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        return entry;
    }

    private void recall(List<QdrantClient.QdrantHit> hits, List<MemoryEntryDO> entries) {
        when(qdrant.search(eq(MemoryIndexPoints.COLLECTION_ALIAS), any(),
                any(QdrantClient.QdrantFilter.class), any(Integer.class)))
                .thenReturn(hits);
        when(entryMapper.selectByIds(anyList())).thenReturn(entries);
    }

    private static QdrantClient.QdrantHit hit(long id, double score) {
        return new QdrantClient.QdrantHit(id, score, java.util.Map.of());
    }

    private MemoryRetriever.MemorySearchResult search(String queryText) {
        return retriever.search(GROUP, new MemoryRetriever.MemorySearchQuery(queryText, null, null, null));
    }

    @Test
    void 重排相似度因子_高相似度条目排前() {
        LocalDateTime occurred = LocalDateTime.of(2026, 11, 24, 12, 0);
        recall(List.of(hit(1, 0.9), hit(2, 0.5)), List.of(
                entry(2, "低相似条目", 50, occurred, null),
                entry(1, "高相似条目", 50, occurred, null)));

        MemoryRetriever.MemorySearchResult result = search("李四");

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("高相似条目");
        // 第一行（最高分）是高相似条目
        assertThat(firstMemoryLine(result)).contains("高相似条目");
        assertThat(result.content()).contains("[2026-11-24] (事实) 高相似条目");
    }

    @Test
    void 重排衰减因子_常被检索的条目排前() {
        // 相似度与强度相同；条目 1 上次访问在 5 天前、条目 2 昨天刚被检索——
        // 检索热度抵消衰减（双时间轴的检索轴），条目 2 排前
        LocalDateTime occurred = LocalDateTime.of(2026, 11, 15, 12, 0);
        recall(List.of(hit(1, 0.8), hit(2, 0.8)), List.of(
                entry(1, "很久没被想起", 50, occurred, LocalDateTime.of(2026, 11, 20, 12, 0)),
                entry(2, "昨天刚被检索", 50, occurred, LocalDateTime.of(2026, 11, 24, 12, 0))));

        MemoryRetriever.MemorySearchResult result = search("记忆");

        assertThat(firstMemoryLine(result)).contains("昨天刚被检索");
    }

    @Test
    void 重排强度因子_高强度条目排前() {
        LocalDateTime occurred = LocalDateTime.of(2026, 11, 24, 12, 0);
        recall(List.of(hit(1, 0.8), hit(2, 0.8)), List.of(
                entry(1, "低强度条目", 10, occurred, null),
                entry(2, "高强度条目", 90, occurred, null)));

        MemoryRetriever.MemorySearchResult result = search("记忆");

        assertThat(firstMemoryLine(result)).contains("高强度条目");
    }

    @Test
    void 隔离三元组过滤强制注入_调用方不传也被补上_可选过滤叠加() {
        recall(List.of(), List.of());
        retriever.search(GROUP, new MemoryRetriever.MemorySearchQuery(
                "李四", MemoryEntryDO.TYPE_PREFERENCE, "u2", 30));

        ArgumentCaptor<QdrantClient.QdrantFilter> filter =
                ArgumentCaptor.forClass(QdrantClient.QdrantFilter.class);
        verify(qdrant).search(eq(MemoryIndexPoints.COLLECTION_ALIAS), any(),
                filter.capture(), eq(50));
        List<String> conditions = filter.getValue().keywords().stream()
                .map(k -> k.field() + "=" + k.value()).toList();
        // 跨群隔离是结构保证——platform/botId/groupId 永远在过滤里
        assertThat(conditions).contains("platform=qq", "botId=10000", "groupId=80001");
        assertThat(conditions).contains("memoryType=preference", "userId=u2");
        // withinDays=30 → occurredAt 下界（2026-10-26T04:00Z 的 epoch 毫秒）
        assertThat(filter.getValue().ranges()).hasSize(1);
        QdrantClient.QdrantFilter.RangeCondition range = filter.getValue().ranges().get(0);
        assertThat(range.field()).isEqualTo("occurredAt");
        assertThat(range.from()).isEqualTo(Instant.parse("2026-10-26T04:00:00Z").toEpochMilli());
        assertThat(range.to()).isNull();
    }

    @Test
    void 过采样上限取参数_候选超topKMax截八条_命中进返回结果才强化() {
        List<QdrantClient.QdrantHit> hits = new ArrayList<>();
        List<MemoryEntryDO> entries = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            hits.add(hit(i, 0.9 - i * 0.01));
            entries.add(entry(i, "条目" + i, 50,
                    LocalDateTime.of(2026, 11, 24, 12, 0), null));
        }
        recall(hits, entries);

        MemoryRetriever.MemorySearchResult result = search("记忆");

        // top 5~8：10 条候选只出 8 行（topKMax 默认 8）
        assertThat(memoryLines(result)).hasSize(8);
        assertThat(firstMemoryLine(result)).contains("条目1");
        // 强化只作用于命中进返回结果的条目（8 条，不含被截掉的 9/10）
        ArgumentCaptor<List<Long>> ids = idsCaptor();
        verify(entryMapper).reinforceBatch(ids.capture(), eq(5),
                eq(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)));
        assertThat(ids.getValue()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void token预算裁剪_超长正文截断自描述_总输出不超限() {
        // 每条正文 ~400 token × 8 条：单条超均摊预算（1500/6=250）被截断、总量压在预算内
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longText.append("记忆正文内容");
        }
        List<QdrantClient.QdrantHit> hits = new ArrayList<>();
        List<MemoryEntryDO> entries = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            hits.add(hit(i, 0.9));
            entries.add(entry(i, longText + "编号" + i, 50,
                    LocalDateTime.of(2026, 11, 24, 12, 0), null));
        }
        recall(hits, entries);

        MemoryRetriever.MemorySearchResult result = search("记忆");

        assertThat(result.content()).contains("（内容过长已截断）");
        assertThat(TokenEstimator.estimate(result.content()))
                .isLessThanOrEqualTo(1500 + TokenEstimator.estimate(
                        DelimitHeaders.wrap("", DelimitHeaders.Src.MEMORY)));
        // 重正文场景：单条截到均摊预算后预算在 topKMin=5 条耗尽——保底下限 5 条（契约区间 5~8）
        assertThat(memoryLines(result)).hasSize(5);
    }

    @Test
    void 结果整体定界包裹_记忆通道XML标签齐全() {
        recall(List.of(hit(1, 0.9)), List.of(entry(1, "李四是程序员", 50,
                LocalDateTime.of(2026, 11, 24, 12, 0), null)));

        MemoryRetriever.MemorySearchResult result = search("李四");

        assertThat(result.error()).isFalse();
        assertThat(result.content()).startsWith("<untrusted src=\"memory\">");
        assertThat(result.content()).endsWith("</untrusted>");
    }

    @Test
    void 空召回_正常业务文本非error() {
        recall(List.of(), List.of());

        MemoryRetriever.MemorySearchResult result = search("不存在的记忆");

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("没有找到相关记忆");
        verify(entryMapper, never()).reinforceBatch(anyList(), any(Integer.class), any());
    }

    @Test
    void 归档与被取代条目_物理点仍在索引但现读后退出检索() {
        // 归档退出检索不动物理点——Qdrant 仍召回，MySQL 现读按 status 过滤
        MemoryEntryDO archived = entry(1, "已归档的旧事", 50,
                LocalDateTime.of(2026, 11, 1, 12, 0), null);
        archived.setStatus(MemoryEntryDO.STATUS_ARCHIVED);
        MemoryEntryDO superseded = entry(2, "被取代的旧说法", 50,
                LocalDateTime.of(2026, 11, 1, 12, 0), null);
        superseded.setStatus(MemoryEntryDO.STATUS_SUPERSEDED);
        recall(List.of(hit(1, 0.9), hit(2, 0.88), hit(3, 0.8)), List.of(
                archived, superseded,
                entry(3, "仍然有效的记忆", 50, LocalDateTime.of(2026, 11, 24, 12, 0), null)));

        MemoryRetriever.MemorySearchResult result = search("记忆");

        assertThat(result.content()).contains("仍然有效的记忆");
        assertThat(result.content()).doesNotContain("已归档的旧事").doesNotContain("被取代的旧说法");
        ArgumentCaptor<List<Long>> ids = idsCaptor();
        verify(entryMapper).reinforceBatch(ids.capture(), eq(5), any());
        assertThat(ids.getValue()).containsExactly(3L);
    }

    @Test
    void 查询向量化失败_isError返回不抛异常() {
        when(qdrant.search(any(), any(), any(), any(Integer.class)))
                .thenThrow(new RuntimeException("qdrant down"));

        MemoryRetriever.MemorySearchResult result = search("李四");

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("memory.qdrant.unavailable");
    }

    @Test
    void 现读后候选全失效_按空召回处理() {
        MemoryEntryDO archived = entry(1, "已归档", 50,
                LocalDateTime.of(2026, 11, 1, 12, 0), null);
        archived.setStatus(MemoryEntryDO.STATUS_ARCHIVED);
        recall(List.of(hit(1, 0.9)), List.of(archived));

        MemoryRetriever.MemorySearchResult result = search("记忆");

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("没有找到相关记忆");
    }

    // ===== 断言辅助：定界包裹内的记忆行 =====

    private List<String> memoryLines(MemoryRetriever.MemorySearchResult result) {
        // 剥 US7 XML 定界外壳（开标签与首条数据同行、闭标签与末条同行）
        String content = result.content();
        int openEnd = content.indexOf('>');
        int closeStart = content.lastIndexOf("</untrusted>");
        if (openEnd >= 0 && closeStart > openEnd) {
            content = content.substring(openEnd + 1, closeStart);
        }
        String[] lines = content.split("\n");
        List<String> memoryLines = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("[")) {
                memoryLines.add(line);
            }
        }
        return memoryLines;
    }

    private String firstMemoryLine(MemoryRetriever.MemorySearchResult result) {
        return memoryLines(result).get(0);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Long>> idsCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
