// 守护纪律：接受经 content_version 发布生效可回滚、驳回留档人格不变、状态机只迁一次（T062）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.memory.persona.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T062：审阅门面——接受发布与留痕、驳回留档人格不变、状态机迁移。
 */
class EvolutionReviewTest {

    private PersonaEvolutionSignalsMapper signalsMapper;
    private PersonaEvolutionProposalMapper proposalMapper;
    private PersonaMapper personaMapper;
    private PersonaVersioningService versioningService;
    private EvolutionPipeline pipeline;
    private PersonaDO persona;

    @BeforeEach
    void setUp() {
        signalsMapper = mock(PersonaEvolutionSignalsMapper.class);
        proposalMapper = mock(PersonaEvolutionProposalMapper.class);
        personaMapper = mock(PersonaMapper.class);
        versioningService = mock(PersonaVersioningService.class);
        persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("测试人格");
        persona.setDefinition("""
                {"basicInfo": {"name": "测试人格"},
                 "attentionPreference": {"talkativity": 50},
                 "emotionConfig": {"defaultLabel": "平静", "sensitivity": 1.0}}""");
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(persona));
        pipeline = new EvolutionPipeline(signalsMapper, proposalMapper, personaMapper,
                versioningService, null, null, null, Clock.systemUTC());
    }

    private PersonaEvolutionProposalDO pendingProposal(String itemsJson) {
        PersonaEvolutionProposalDO proposal = new PersonaEvolutionProposalDO();
        proposal.setId(900L);
        proposal.setPersonaId(1L);
        proposal.setStatus(PersonaEvolutionProposalDO.STATUS_PENDING_REVIEW);
        proposal.setProposalItems(itemsJson);
        when(proposalMapper.selectById(900L)).thenReturn(proposal);
        when(proposalMapper.updateReview(eq(900L), anyString(), any(), any())).thenReturn(1);
        return proposal;
    }

    @Test
    void 接受_条目写进definition并走发布() throws Exception {
        pendingProposal("[{\"fieldPath\":\"attentionPreference.talkativity\","
                + "\"suggestedValue\":65,\"currentValue\":50,\"evidence\":\"互动率高\","
                + "\"expectedEffect\":\"更健谈\",\"itemStatus\":\"proposed\"}]");

        pipeline.acceptProposal(900L, "admin", "看着合理");

        // 条目应用：definition 的健谈度 50 → 65
        ArgumentCaptor<PersonaDO> saved = ArgumentCaptor.forClass(PersonaDO.class);
        verify(personaMapper).updatePersona(saved.capture());
        JsonNode definition = JsonMappers.shared().readTree(saved.getValue().getDefinition());
        assertEquals(65, definition.path("attentionPreference").path("talkativity").asDouble(), 0.001);
        // 发布走 PersonaVersioningService（content_version 留痕、版本递增、可回滚）
        verify(versioningService).publishPersona(1L, "admin", "看着合理");
        verify(proposalMapper).updateReview(900L,
                PersonaEvolutionProposalDO.STATUS_ACCEPTED, "admin", "看着合理");
    }

    @Test
    void 接受时_可写集外条目被跳过_definition不动() throws Exception {
        pendingProposal("[{\"fieldPath\":\"selfSetting\",\"suggestedValue\":0,"
                + "\"currentValue\":0,\"evidence\":\"x\",\"expectedEffect\":\"y\"}]");

        pipeline.acceptProposal(900L, "admin", null);

        ArgumentCaptor<PersonaDO> saved = ArgumentCaptor.forClass(PersonaDO.class);
        verify(personaMapper).updatePersona(saved.capture());
        JsonNode definition = JsonMappers.shared().readTree(saved.getValue().getDefinition());
        assertEquals("测试人格", definition.path("basicInfo").path("name").asText());
        assertEquals(50, definition.path("attentionPreference").path("talkativity").asInt());
    }

    @Test
    void 驳回_留档人格不变() {
        pendingProposal("[]");
        pipeline.rejectProposal(900L, "admin", "太激进了");

        verify(proposalMapper).updateReview(900L,
                PersonaEvolutionProposalDO.STATUS_REJECTED, "admin", "太激进了");
        verify(personaMapper, never()).updatePersona(any());
        verify(versioningService, never()).publishPersona(anyLong(), any(), any());
    }

    @Test
    void 状态机只迁一次_已审提案再操作被拒() {
        PersonaEvolutionProposalDO reviewed = pendingProposal("[]");
        reviewed.setStatus(PersonaEvolutionProposalDO.STATUS_ACCEPTED);
        when(proposalMapper.updateReview(eq(900L), anyString(), any(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> pipeline.acceptProposal(900L, "admin", null));
        assertThrows(IllegalStateException.class,
                () -> pipeline.rejectProposal(900L, "admin", null));
    }

    @Test
    void 提案不存在_直接拒绝操作() {
        when(proposalMapper.selectById(404L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> pipeline.acceptProposal(404L, "a", null));
        assertThrows(IllegalArgumentException.class, () -> pipeline.rejectProposal(404L, "a", null));
    }

    @Test
    void 修改后接受_编辑条目先行落库再走接受() {
        pendingProposal("[]");
        pipeline.editProposalItems(900L, "[{\"fieldPath\":\"emotionConfig.sensitivity\","
                + "\"suggestedValue\":1.2,\"currentValue\":1.0,\"evidence\":\"手动调整\","
                + "\"expectedEffect\":\"更敏感\",\"itemStatus\":\"proposed\"}]");
        verify(proposalMapper).updateProposalItems(eq(900L), anyString());
    }
}
