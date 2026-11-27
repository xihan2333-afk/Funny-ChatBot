package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.bootstrap.CredentialCodec;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.PayloadException;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.llm.slot.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmReembedAdminTest {

    @SuppressWarnings("unchecked")
    private LlmAdminService buildService(ModelConfigMapper modelMapper,
                                         ModelSlotMapper slotMapper,
                                         ReembedJobMapper reembedJobMapper,
                                         QdrantClient qdrantClient) {
        ObjectProvider<QdrantClient> qdrantProvider = mock(ObjectProvider.class);
        when(qdrantProvider.getIfAvailable()).thenReturn(qdrantClient);
        ObjectProvider<cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper> memProvider = mock(ObjectProvider.class);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-27T05:00:00Z"), ZoneId.of("Asia/Shanghai"));
        return new LlmAdminService(
                mock(ProviderConfigMapper.class),
                modelMapper,
                slotMapper,
                mock(CredentialMapper.class),
                reembedJobMapper,
                mock(ModelCatalog.class),
                new CredentialCodec("12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8)),
                mock(ApplicationEventPublisher.class),
                fixedClock,
                new IdGenerator(1, fixedClock),
                qdrantProvider,
                memProvider
        );
    }

    @Test
    void blocksEmbeddingSlotUpdateOnDimensionMismatchAndReturns400Payload() {
        ModelConfigMapper modelMapper = mock(ModelConfigMapper.class);
        ModelConfigDO model = new ModelConfigDO();
        model.setId(12L);
        model.setProviderCode("openai");
        model.setModelId("text-embedding-3-large");
        model.setCapability("{\"embeddingDimension\":3072}");
        when(modelMapper.selectAll()).thenReturn(List.of(model));

        QdrantClient qdrant = mock(QdrantClient.class);
        when(qdrant.describeCollection("memory_entry"))
                .thenReturn(new QdrantClient.QdrantCollectionInfo(1536, 100L, "green"));

        LlmAdminService service = buildService(modelMapper, mock(ModelSlotMapper.class), mock(ReembedJobMapper.class), qdrant);

        assertThatThrownBy(() -> service.updateSlot("embedding",
                new LlmAdminService.SlotUpdateRequest("openai", "text-embedding-3-large", "{}", true)))
                .isInstanceOf(PayloadException.class)
                .satisfies(ex -> {
                    PayloadException pe = (PayloadException) ex;
                    assertThat(pe.getErrorCode()).isEqualTo("llm.embedding_dimension_mismatch");
                    assertThat(pe.getPayload()).containsEntry("currentDimension", 1536);
                    assertThat(pe.getPayload()).containsEntry("targetDimension", 3072);
                });
    }

    @Test
    void rejectsConcurrentReembedWith409ConflictCode() {
        ModelConfigMapper modelMapper = mock(ModelConfigMapper.class);
        ModelConfigDO model = new ModelConfigDO();
        model.setId(12L);
        model.setProviderCode("openai");
        model.setModelId("text-embedding-3-large");
        model.setCapability("{\"embeddingDimension\":3072}");
        when(modelMapper.selectAll()).thenReturn(List.of(model));

        ReembedJobMapper jobMapper = mock(ReembedJobMapper.class);
        when(jobMapper.countActive()).thenReturn(1L);

        LlmAdminService service = buildService(modelMapper, mock(ModelSlotMapper.class), jobMapper, mock(QdrantClient.class));

        assertThatThrownBy(() -> service.startReembed(12L, true, "admin"))
                .isInstanceOf(ChatBotException.class)
                .extracting("errorCode")
                .isEqualTo("mgmt.reembed_in_progress");
    }
}
