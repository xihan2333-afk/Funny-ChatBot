// 守护纪律：embedding 换绑维度不符必须阻断不静默生效、DeepSeek 无 embedding API 拒绝、
// 集合登记缺失首启自检回填（contracts/memory-retrieval.md §4、research R3 双源校验）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.SystemParamDO;
import cn.edu.scut.chatbot.bootstrap.SystemParamMapper;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.capability.EmbeddingClient;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.llm.slot.SlotResolver;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.ModelCapabilities;
import cn.edu.scut.chatbot.loop.ModelConfig;
import cn.edu.scut.chatbot.loop.ModelRef;
import cn.edu.scut.chatbot.memory.retrieve.CollectionGuard;
import cn.edu.scut.chatbot.memory.retrieve.CollectionGuardImpl;
import cn.edu.scut.chatbot.memory.retrieve.MemoryRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionGuardTest {

    private QdrantClient qdrant;
    private SlotResolver slotResolver;
    private EmbeddingClient embeddingClient;
    private SystemParamService systemParams;
    private SystemParamMapper systemParamMapper;
    private MemoryRetriever retriever;
    private CollectionGuard guard;

    @BeforeEach
    void setUp() {
        qdrant = mock(QdrantClient.class);
        slotResolver = mock(SlotResolver.class);
        embeddingClient = mock(EmbeddingClient.class);
        systemParams = mock(SystemParamService.class);
        systemParamMapper = mock(SystemParamMapper.class);
        retriever = mock(MemoryRetriever.class);
        guard = new CollectionGuardImpl(qdrant, slotResolver, embeddingClient,
                systemParams, systemParamMapper, retriever);
        when(systemParams.getString(eq("memory.qdrant.collection_meta"), any())).thenReturn(null);
    }

    private void stubEmbeddingSlot(String modelId) {
        ModelRef modelRef = new ModelRef(ModelRef.DIALECT_OPENAI_COMPAT, "zai", modelId,
                "http://upstream", new ModelCapabilities(false, false, 128000, 4096), null, "embedding");
        when(slotResolver.resolve(ModelCatalog.SLOT_EMBEDDING))
                .thenReturn(new ModelConfig(modelRef, GenerationParams.defaults()));
    }

    private void stubProbeDim(int dim) {
        when(embeddingClient.embed(eq(ModelCatalog.SLOT_EMBEDDING), any(), anyList()))
                .thenReturn(List.of(new float[dim]));
    }

    @Test
    void 集合不存在_按当前槽位探测维度创建物理v1并挂别名_回填登记() {
        when(qdrant.describeCollection("memory_entry")).thenReturn(null);
        when(qdrant.describeCollection("memory_entry_v1")).thenReturn(null);
        stubEmbeddingSlot("embedding-3");
        stubProbeDim(1024);

        guard.ensureCollection();

        verify(qdrant).createCollection(eq("memory_entry_v1"), eq(1024),
                eq(cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints.STABLE_PAYLOAD_INDEXES));
        verify(qdrant).switchAlias("memory_entry", "memory_entry_v1");
        ArgumentCaptor<SystemParamDO> param = ArgumentCaptor.forClass(SystemParamDO.class);
        verify(systemParamMapper).upsertParam(param.capture());
        assertThat(param.getValue().getParamValue())
                .contains("\"dim\":1024").contains("\"embedModelCode\":\"embedding-3\"");
        verify(systemParams).refresh();
    }

    @Test
    void 别名缺失但物理v1存在_不重复建集合_只补挂别名() {
        when(qdrant.describeCollection("memory_entry")).thenReturn(null);
        when(qdrant.describeCollection("memory_entry_v1"))
                .thenReturn(new QdrantClient.QdrantCollectionInfo(8, 0, "green"));
        stubEmbeddingSlot("embedding-3");
        stubProbeDim(8);

        guard.ensureCollection();

        verify(qdrant, never()).createCollection(anyString(), anyInt(), any());
        verify(qdrant).switchAlias("memory_entry", "memory_entry_v1");
    }

    @Test
    void 登记缺失但集合存在_按实际维度回填登记不重建集合() {
        when(qdrant.describeCollection("memory_entry"))
                .thenReturn(new QdrantClient.QdrantCollectionInfo(768, 100, "green"));

        guard.ensureCollection();

        verify(qdrant, never()).createCollection(anyString(), anyInt(), any());
        ArgumentCaptor<SystemParamDO> param = ArgumentCaptor.forClass(SystemParamDO.class);
        verify(systemParamMapper).upsertParam(param.capture());
        assertThat(param.getValue().getParamValue()).contains("\"dim\":768");
    }

    @Test
    void 登记与实际维度不一致_启动自检告警不抛异常() {
        when(systemParams.getString(eq("memory.qdrant.collection_meta"), any()))
                .thenReturn("{\"collection\":\"memory_entry\",\"dim\":1024,\"embedModelCode\":\"m1\"}");
        when(qdrant.describeCollection("memory_entry"))
                .thenReturn(new QdrantClient.QdrantCollectionInfo(768, 100, "green"));

        guard.ensureCollection();   // 不抛——启动自检不因漂移崩进程，迁移路径由运营触发

        verify(systemParamMapper, never()).upsertParam(any());
    }

    @Test
    void embedding槽位不可探测_集合创建推迟不抛异常() {
        when(qdrant.describeCollection("memory_entry")).thenReturn(null);
        when(slotResolver.resolve(ModelCatalog.SLOT_EMBEDDING))
                .thenThrow(new ModelCatalog.SlotResolutionException("槽位未绑定 slot=embedding"));

        guard.ensureCollection();

        verify(qdrant, never()).createCollection(anyString(), anyInt(), any());
    }

    @Test
    void 换绑新维度与登记不符_阻断不静默生效() {
        when(systemParams.getString(eq("memory.qdrant.collection_meta"), any()))
                .thenReturn("{\"collection\":\"memory_entry\",\"dim\":1024,\"embedModelCode\":\"m1\"}");

        assertThatThrownBy(() -> guard.validateEmbeddingModelChange("zai", "embedding-4", 768))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("768")
                .hasMessageContaining("1024")
                .hasMessageContaining("迁移");
    }

    @Test
    void 换绑维度与登记一致_放行() {
        when(systemParams.getString(eq("memory.qdrant.collection_meta"), any()))
                .thenReturn("{\"collection\":\"memory_entry\",\"dim\":1024,\"embedModelCode\":\"m1\"}");

        guard.validateEmbeddingModelChange("zai", "embedding-3-new", 1024);
    }

    @Test
    void 换绑DeepSeek供应商_无embeddingAPI直接拒绝() {
        when(systemParams.getString(eq("memory.qdrant.collection_meta"), any()))
                .thenReturn("{\"collection\":\"memory_entry\",\"dim\":1024,\"embedModelCode\":\"m1\"}");

        assertThatThrownBy(() -> guard.validateEmbeddingModelChange("deepseek", "deepseek-chat", 1024))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("DeepSeek")
                .hasMessageContaining("智谱");
    }

    @Test
    void 迁移到新维度_新建版本集合并全量重灌后切别名_登记更新() {
        when(systemParams.getString(eq("memory.qdrant.collection_meta"), any()))
                .thenReturn("{\"collection\":\"memory_entry\",\"dim\":1024,\"embedModelCode\":\"m1\"}");
        when(qdrant.describeCollection("memory_entry_v2")).thenReturn(null);
        stubEmbeddingSlot("embedding-4");
        stubProbeDim(768);
        when(retriever.rebuildCollection("memory_entry_v2", true))
                .thenReturn(new MemoryRetriever.RebuildReport(100, 100, "memory_entry_v2", true));

        MemoryRetriever.RebuildReport report = guard.migrateToNewDimension();

        assertThat(report.totalEntries()).isEqualTo(100);
        assertThat(report.aliasSwitched()).isTrue();
        verify(retriever).rebuildCollection("memory_entry_v2", true);
        ArgumentCaptor<SystemParamDO> param = ArgumentCaptor.forClass(SystemParamDO.class);
        verify(systemParamMapper).upsertParam(param.capture());
        assertThat(param.getValue().getParamValue())
                .contains("\"dim\":768").contains("\"embedModelCode\":\"embedding-4\"");
    }

    @Test
    void 槽位维度与登记一致_迁移为无操作() {
        when(systemParams.getString(eq("memory.qdrant.collection_meta"), any()))
                .thenReturn("{\"collection\":\"memory_entry\",\"dim\":1024,\"embedModelCode\":\"m1\"}");
        stubEmbeddingSlot("embedding-3");
        stubProbeDim(1024);

        MemoryRetriever.RebuildReport report = guard.migrateToNewDimension();

        assertThat(report.totalEntries()).isZero();
        verify(retriever, never()).rebuildCollection(anyString(), anyBoolean());
    }
}
