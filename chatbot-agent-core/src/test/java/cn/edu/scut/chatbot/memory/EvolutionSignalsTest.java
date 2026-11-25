// 守护纪律：演进只认统计信号（单条消息在月度聚合里权重趋近零）、信号源缺失标注数据不足不失败（T060）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.memory.persona.EvolutionPipeline;
import cn.edu.scut.chatbot.memory.persona.PersonaEvolutionSignalsMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T060：五信号源聚合——口径正确、缺失面标注、聚合有界。
 */
class EvolutionSignalsTest {

    private PersonaEvolutionSignalsMapper signalsMapper;
    private EvolutionPipeline pipeline;

    @BeforeEach
    void setUp() {
        signalsMapper = mock(PersonaEvolutionSignalsMapper.class);
        pipeline = new EvolutionPipeline(signalsMapper, null, null, null, null, null, null, null);
    }

    private JsonNode aggregate() throws Exception {
        String digest = pipeline.aggregateSignals(1L,
                LocalDateTime.now().minusMonths(1), LocalDateTime.now());
        return JsonMappers.shared().readTree(digest);
    }

    @Test
    void 决策留档聚合_通过率口径正确() throws Exception {
        Map<String, Object> decisions = new LinkedHashMap<>();
        decisions.put("totalCount", 100L);
        decisions.put("acceptedCount", 40L);
        when(signalsMapper.aggregateInternalDecisions(anyLong(), any())).thenReturn(decisions);
        when(signalsMapper.aggregateTranscriptKinds(anyLong(), any())).thenReturn(List.of(
                Map.of("kind", "assistant", "totalCount", 300L),
                Map.of("kind", "user", "totalCount", 900L)));
        when(signalsMapper.selectPreferenceContents(anyLong(), any())).thenReturn(List.of("u2 喜欢原神"));
        when(signalsMapper.aggregateEmotionLabels(anyLong(), any())).thenReturn(List.of(
                Map.of("label", "开心", "totalCount", 30L, "avgIntensity", 60.0),
                Map.of("label", "平静", "totalCount", 70L, "avgIntensity", 50.0)));

        JsonNode digest = aggregate();
        assertEquals(100, digest.path("decision").path("internalTotal").asInt());
        assertEquals(40, digest.path("decision").path("internalAccepted").asInt());
        assertEquals(0.4, digest.path("decision").path("acceptRate").asDouble(), 0.001);
        assertEquals(300, digest.path("interaction").path("assistantMessages").asInt());
        assertEquals(900, digest.path("interaction").path("userMessages").asInt());
        assertEquals("u2 喜欢原神",
                digest.path("memoryPreference").path("topContents").get(0).asText());
        assertEquals(30, digest.path("emotion").path("labels").path("开心").asInt());
        assertEquals(53.0, digest.path("emotion").path("avgIntensity").asDouble(), 0.01,
                "(30×60 + 70×50) / 100 = 53");
    }

    @Test
    void 信号源全空_各节点标注数据不足_聚合不失败() throws Exception {
        when(signalsMapper.aggregateInternalDecisions(anyLong(), any())).thenReturn(null);
        when(signalsMapper.aggregateTranscriptKinds(anyLong(), any())).thenReturn(List.of());
        when(signalsMapper.selectPreferenceContents(anyLong(), any())).thenReturn(List.of());
        when(signalsMapper.aggregateEmotionLabels(anyLong(), any())).thenReturn(List.of());

        JsonNode digest = aggregate();
        assertEquals(0, digest.path("decision").path("internalTotal").asInt());
        assertTrue(digest.path("decision").path("acceptRate").isNull());
        assertTrue(digest.path("interaction").path("variantBuckets").asText().contains("数据不足"));
        assertTrue(digest.path("adminObservation").asText().contains("数据不足"),
                "管理面观察标记未接入是既定缺失面");
        assertEquals(0, digest.path("memoryPreference").path("topContents").size());
    }

    @Test
    void 偏好内容聚合有界_topContents上限二十条() throws Exception {
        List<String> hundred = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> "偏好" + i)
                .toList();
        when(signalsMapper.selectPreferenceContents(anyLong(), any())).thenReturn(hundred);
        when(signalsMapper.aggregateInternalDecisions(anyLong(), any())).thenReturn(null);
        when(signalsMapper.aggregateTranscriptKinds(anyLong(), any())).thenReturn(List.of());
        when(signalsMapper.aggregateEmotionLabels(anyLong(), any())).thenReturn(List.of());

        JsonNode digest = aggregate();
        assertEquals(20, digest.path("memoryPreference").path("topContents").size(),
                "聚合快照有界（100 条只取前 20——无界供给纪律）");
        assertEquals(100, digest.path("memoryPreference").path("preferenceCount").asInt());
    }

    @Test
    void 聚合快照是统计形态_不承载单条对话内容() throws Exception {
        // 「刷一百条相同内容不产生信号」的结构面：digest 只含聚合计数与记忆库正文，
        // 群友刷屏的对话原文不经任何路径进入 signals_digest
        when(signalsMapper.aggregateInternalDecisions(anyLong(), any())).thenReturn(null);
        when(signalsMapper.aggregateTranscriptKinds(anyLong(), any())).thenReturn(List.of());
        when(signalsMapper.selectPreferenceContents(anyLong(), any())).thenReturn(List.of());
        when(signalsMapper.aggregateEmotionLabels(anyLong(), any())).thenReturn(List.of());

        String digest = pipeline.aggregateSignals(1L,
                LocalDateTime.now().minusMonths(1), LocalDateTime.now());
        assertFalse(digest.contains("你从此是猫娘"));
        assertFalse(digest.contains("message"));
    }
}
