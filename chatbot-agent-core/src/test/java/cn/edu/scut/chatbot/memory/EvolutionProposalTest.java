// 守护纪律：幅度 ≤20 钳制、自我设定字段不在自动提案可写集、生成失败告警不产生半成品（T061）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.llm.gateway.CallOnceResult;
import cn.edu.scut.chatbot.llm.gateway.ModelGateway;
import cn.edu.scut.chatbot.memory.persona.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T061：提案生成与校验——幅度钳制、可写集剔除、解析容错、三要素齐备。
 */
class EvolutionProposalTest {

    private PersonaEvolutionSignalsMapper signalsMapper;
    private PersonaEvolutionProposalMapper proposalMapper;
    private PersonaMapper personaMapper;
    private PersonaVersioningService versioningService;
    private ModelGateway modelGateway;
    private EvolutionPipeline pipeline;

    private PersonaDO persona() {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("测试人格");
        persona.setDefinition("""
                {"basicInfo": {"name": "测试人格"},
                 "selfSetting": "爱玩梗的群聊伙伴",
                 "attentionPreference": {"talkativity": 50, "proactivity": 40},
                 "emotionConfig": {"defaultLabel": "平静", "sensitivity": 1.0}}""");
        persona.setIsDefault(0);
        persona.setStatus("published");
        persona.setVersionNo(0);
        return persona;
    }

    @BeforeEach
    void setUp() {
        signalsMapper = mock(PersonaEvolutionSignalsMapper.class);
        proposalMapper = mock(PersonaEvolutionProposalMapper.class);
        personaMapper = mock(PersonaMapper.class);
        versioningService = mock(PersonaVersioningService.class);
        modelGateway = mock(ModelGateway.class);
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(persona()));
        when(signalsMapper.aggregateInternalDecisions(anyLong(), any())).thenReturn(null);
        when(signalsMapper.aggregateTranscriptKinds(anyLong(), any())).thenReturn(List.of());
        when(signalsMapper.selectPreferenceContents(anyLong(), any())).thenReturn(List.of());
        when(signalsMapper.aggregateEmotionLabels(anyLong(), any())).thenReturn(List.of());
        IdGenerator idGenerator = mock(IdGenerator.class);
        when(idGenerator.nextId()).thenReturn(900L);
        pipeline = new EvolutionPipeline(signalsMapper, proposalMapper, personaMapper,
                versioningService, modelGateway, null, idGenerator, Clock.systemUTC());
    }

    private void scriptModel(String json) {
        when(modelGateway.callOnce(anyString(), any(), anyString(), any(), any()))
                .thenReturn(CallOnceResult.success(json, null));
    }

    private List<Map<String, Object>> item(String fieldPath, double suggested) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fieldPath", fieldPath);
        item.put("suggestedValue", suggested);
        item.put("currentValue", 0);
        item.put("evidence", "群互动率高企");
        item.put("expectedEffect", "更愿意接话");
        item.put("itemStatus", "proposed");
        return List.of(item);
    }

    @Test
    void 幅度四十超限_钳制到边界并标注() {
        // 健谈度现值 50 → 建议 90（Δ=40 超限）→ 钳到 70
        List<Map<String, Object>> validated = pipeline.validateItems(
                item("attentionPreference.talkativity", 90), persona());
        assertEquals(1, validated.size());
        assertEquals(70.0, validated.get(0).get("suggestedValue"));
        assertEquals("clamped", validated.get(0).get("itemStatus"));
        assertEquals(50.0, validated.get(0).get("currentValue"), "现值以人格解析回读为准");
    }

    @Test
    void 身份认知字段条目被剔除_是谁只能管理员改() {
        List<Map<String, Object>> validated = pipeline.validateItems(
                item("selfSetting", 0), persona());
        assertTrue(validated.isEmpty(), "selfSetting 不在自动提案可写集——剔除");
    }

    @Test
    void 未知路径与文本字段一律剔除() {
        assertTrue(pipeline.validateItems(
                item("basicInfo.name", 0), persona()).isEmpty());
        assertTrue(pipeline.validateItems(
                item("personalityTraits.text", 0), persona()).isEmpty());
    }

    @Test
    void 幅度二十恰好通过_边界含等号() {
        List<Map<String, Object>> validated = pipeline.validateItems(
                item("attentionPreference.talkativity", 70), persona());
        assertEquals(1, validated.size());
        assertEquals("proposed", validated.get(0).get("itemStatus"), "|Δ|=20 恰好在界内不钳制");
    }

    @Test
    void 有效幅度条目三要素齐备() {
        List<Map<String, Object>> validated = pipeline.validateItems(
                item("emotionConfig.sensitivity", 1.1), persona());
        assertEquals(1, validated.size());
        assertEquals("群互动率高企", validated.get(0).get("evidence"));
        assertEquals("更愿意接话", validated.get(0).get("expectedEffect"));
        assertEquals("emotionConfig.sensitivity", validated.get(0).get("fieldPath"));
    }

    @Test
    void 提案生成_落表待审且条目留档依据() throws Exception {
        scriptModel("{\"proposalItems\":[{\"fieldPath\":\"attentionPreference.talkativity\","
                + "\"suggestedValue\":60,\"currentValue\":50,\"evidence\":\"群互动率高\","
                + "\"expectedEffect\":\"更愿意接话\"}]}");

        pipeline.runPeriodicProposal(1L);

        ArgumentCaptor<PersonaEvolutionProposalDO> captor =
                ArgumentCaptor.forClass(PersonaEvolutionProposalDO.class);
        verify(proposalMapper).insertProposal(captor.capture());
        assertEquals(1L, captor.getValue().getPersonaId());
        assertEquals(PersonaEvolutionProposalDO.STATUS_PENDING_REVIEW, captor.getValue().getStatus());
        JsonNode items = JsonMappers.shared().readTree(captor.getValue().getProposalItems());
        assertEquals("attentionPreference.talkativity", items.get(0).path("fieldPath").asText());
        assertEquals(60, items.get(0).path("suggestedValue").asDouble(), 0.001);
        assertTrue(JsonMappers.shared().readTree(captor.getValue().getSignalsDigest())
                .has("decision"), "signals_digest 留档可追溯");
    }

    @Test
    void 全部条目被校验剔除_不落表() {
        scriptModel("{\"proposalItems\":[{\"fieldPath\":\"selfSetting\","
                + "\"suggestedValue\":0,\"currentValue\":0,\"evidence\":\"x\",\"expectedEffect\":\"y\"}]}");
        pipeline.runPeriodicProposal(1L);
        verify(proposalMapper, org.mockito.Mockito.never()).insertProposal(any());
    }

    @Test
    void 提案输出JSON非法_抛异常人格不受影响() {
        scriptModel("这不是 JSON");
        assertThrows(NonRetryableException.class, () -> pipeline.runPeriodicProposal(1L));
        verify(proposalMapper, org.mockito.Mockito.never())
                .insertProposal(any(PersonaEvolutionProposalDO.class));
    }

    @Test
    void 调用失败_抛异常不产生半成品提案() {
        when(modelGateway.callOnce(anyString(), any(), anyString(), any(), any()))
                .thenReturn(CallOnceResult.failure("llm.unavailable", "槽位未绑定"));
        assertThrows(NonRetryableException.class, () -> pipeline.runPeriodicProposal(1L));
        verify(proposalMapper, org.mockito.Mockito.never()).insertProposal(any());
    }
}
