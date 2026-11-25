// 守护纪律：懒衰减读时纯函数（拨时钟 2h 强度减半向基线回归、低于 20 视为基线、
// 无任何定时任务参与——纯函数无调度）、群暂停计时顺延、单次与日累计钳制
// （轮番刷情绪也只能推动有限幅度）、词汇集外拒绝、崩溃恢复（无基准自然回基线）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.memory.emotion.EmotionLogDO;
import cn.edu.scut.chatbot.memory.emotion.EmotionLogMapper;
import cn.edu.scut.chatbot.memory.emotion.EmotionServiceImpl;
import cn.edu.scut.chatbot.memory.emotion.EmotionState;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.memory.profile.AffinityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmotionServiceTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "99501");

    /**
     * 可拨时钟（MutableClock 模式——read 是纯函数，拨钟即测衰减）
     */
    private Instant now = Instant.parse("2026-11-25T04:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    };

    private AgentSessionMapper sessionMapper;
    private GroupMapper groupMapper;
    private PersonaResolver personaResolver;
    private EmotionLogMapper emotionLogMapper;
    private AffinityService affinityService;
    private SystemParamService systemParams;
    private EmotionServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AgentSessionMapper.class);
        groupMapper = mock(GroupMapper.class);
        personaResolver = mock(PersonaResolver.class);
        emotionLogMapper = mock(EmotionLogMapper.class);
        affinityService = mock(AffinityService.class);
        systemParams = mock(SystemParamService.class);
        service = new EmotionServiceImpl(sessionMapper, groupMapper, personaResolver,
                emotionLogMapper, affinityService, systemParams,
                mock(IdGenerator.class), clock);

        when(systemParams.getString(anyString(), any())).thenReturn(
                "{\"atMentioned\":4, \"poke\":5, \"emojiReaction\":3, \"noReply\":-3,"
                        + " \"muted\":-8, \"taskRejected\":-4}");
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("小趣");
        persona.setDefinition("{\"emotionConfig\": {\"defaultLabel\": \"平静\","
                + " \"sensitivity\": 1.0}}");
        when(personaResolver.resolve(anyString(), anyString(), anyString())).thenReturn(persona);
        when(emotionLogMapper.sumAbsDeltaSince(anyString(), anyString(), anyString(), any()))
                .thenReturn(0L);
        when(groupMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(null);   // 未暂停
    }

    private void storedEmotion(EmotionState state) {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(7L);
        session.setEmotion(state == null ? null : state.toJson());
        when(sessionMapper.selectByGroup("qq", "10000", "99501")).thenReturn(session);
    }

    @Test
    void 懒衰减_拨时钟两小时强度减半_向基线回归() {
        storedEmotion(new EmotionState("兴奋", 80, "被夸了",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));

        EmotionState immediate = service.read(GROUP);
        assertThat(immediate.intensity()).isEqualTo(80);

        now = now.plus(Duration.ofHours(2));
        EmotionState half = service.read(GROUP);
        assertThat(half.intensity()).isEqualTo(40);

        now = now.plus(Duration.ofHours(2));
        assertThat(service.read(GROUP).intensity()).isEqualTo(20);
    }

    @Test
    void 低于阈值视为基线情绪_label回归基线() {
        storedEmotion(new EmotionState("兴奋", 30, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));
        now = now.plus(Duration.ofHours(4));   // 30 × 0.25 = 7.5 < 20

        EmotionState read = service.read(GROUP);

        assertThat(read.label()).isEqualTo("平静");
        assertThat(read.intensity()).isEqualTo(8);
    }

    @Test
    void 崩溃恢复_无情绪记录_自然回人格基线() {
        when(sessionMapper.selectByGroup("qq", "10000", "99501")).thenReturn(null);

        EmotionState read = service.read(GROUP);

        assertThat(read.label()).isEqualTo("平静");   // 人格基线（重启后心情平复）
    }

    @Test
    void 群暂停_计时顺延_暂停时长不计入衰减() {
        storedEmotion(new EmotionState("兴奋", 80, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));
        now = now.plus(Duration.ofHours(4));
        // 暂停累计 2 小时：4 − 2 = 2 小时有效衰减 → 80 × 0.5 = 40
        GroupDO group = new GroupDO();
        group.setPausedAccumulatedMillis(Duration.ofHours(2).toMillis());
        when(groupMapper.selectByGroup("qq", "10000", "99501")).thenReturn(group);

        assertThat(service.read(GROUP).intensity()).isEqualTo(40);
    }

    @Test
    void 进行中的暂停段也计入顺延() {
        storedEmotion(new EmotionState("兴奋", 80, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));
        now = now.plus(Duration.ofHours(3));
        GroupDO group = new GroupDO();
        group.setPausedAt(LocalDateTime.ofInstant(now.minus(Duration.ofHours(2)), ZoneOffset.UTC));
        when(groupMapper.selectByGroup("qq", "10000", "99501")).thenReturn(group);

        // 3 − 2（进行中暂停）= 1 小时 → 80 × 0.707 ≈ 57（远高于不顺延的 28）
        assertThat(service.read(GROUP).intensity()).isGreaterThan(50);
    }

    @Test
    void 规则通道_默认条目乘敏感度_情绪与好感双落点() {
        storedEmotion(new EmotionState("平静", 50, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));

        boolean applied = service.applyRuleDelta(GROUP, "poke", "u1");

        assertThat(applied).isTrue();
        ArgumentCaptor<String> emotion = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateEmotion(eq(7L), emotion.capture());
        assertThat(emotion.getValue()).contains("\"intensity\":55");   // 50 + 5×1.0
        // 双落点：好感 delta 顺带落画像（poke=5 → magnitude 3 钳制）
        verify(affinityService).applyEventDelta(GROUP, "u1", 3, 5);
        verify(emotionLogMapper).insertLog(any(EmotionLogDO.class));
    }

    @Test
    void 人格覆盖反转方向_默认被戳正面小加_覆盖为烦躁小扣() {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("炸毛人格");
        persona.setDefinition("{\"emotionConfig\": {\"defaultLabel\": \"平静\","
                + " \"sensitivity\": 1.0, \"structuredEventOverrides\": {\"poke\": -4}}}");
        when(personaResolver.resolve(anyString(), anyString(), anyString())).thenReturn(persona);
        storedEmotion(new EmotionState("平静", 50, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));

        service.applyRuleDelta(GROUP, "poke", null);

        ArgumentCaptor<String> emotion = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateEmotion(eq(7L), emotion.capture());
        assertThat(emotion.getValue()).contains("\"intensity\":46");   // 50 − 4
    }

    @Test
    void 敏感度系数为零_规则通道全归零() {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("钝感人格");
        persona.setDefinition("{\"emotionConfig\": {\"sensitivity\": 0}}");
        when(personaResolver.resolve(anyString(), anyString(), anyString())).thenReturn(persona);
        storedEmotion(new EmotionState("平静", 50, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));

        service.applyRuleDelta(GROUP, "poke", null);

        verify(sessionMapper, never()).updateEmotion(any(Long.class), anyString());
    }

    @Test
    void 单次钳制_超出三十的delta被钳() {
        storedEmotion(new EmotionState("平静", 50, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("高敏人格");
        persona.setDefinition("{\"emotionConfig\": {\"sensitivity\": 10}}");
        when(personaResolver.resolve(anyString(), anyString(), anyString())).thenReturn(persona);

        service.applyRuleDelta(GROUP, "poke", null);   // 5 × 10 = 50 → 钳 30

        ArgumentCaptor<String> emotion = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateEmotion(eq(7L), emotion.capture());
        assertThat(emotion.getValue()).contains("\"intensity\":80");   // 50 + 30
    }

    @Test
    void 日累计封顶_轮番刷情绪封顶后拒绝() {
        storedEmotion(new EmotionState("平静", 50, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));
        when(emotionLogMapper.sumAbsDeltaSince(anyString(), anyString(), anyString(), any()))
                .thenReturn(198L);

        boolean applied = service.applyRuleDelta(GROUP, "poke", null);   // 198 + 5 > 200

        assertThat(applied).isFalse();
        verify(sessionMapper, never()).updateEmotion(any(Long.class), anyString());
    }

    @Test
    void 自报通道_词汇集内标签应用_带起因落账() {
        storedEmotion(new EmotionState("平静", 50, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));

        boolean applied = service.applySelfReport(GROUP, "委屈", -20, "被怼了");

        assertThat(applied).isTrue();
        ArgumentCaptor<String> emotion = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateEmotion(eq(7L), emotion.capture());
        assertThat(emotion.getValue()).contains("\"label\":\"委屈\"");
        assertThat(emotion.getValue()).contains("\"intensity\":30");
        ArgumentCaptor<EmotionLogDO> logRow = ArgumentCaptor.forClass(EmotionLogDO.class);
        verify(emotionLogMapper).insertLog(logRow.capture());
        assertThat(logRow.getValue().getSource()).isEqualTo("self_report");
        assertThat(logRow.getValue().getDelta()).isEqualTo(-20);
    }

    @Test
    void 自报通道_词汇集外标签拒绝_不应用不落账() {
        assertThatThrownBy(() -> service.applySelfReport(GROUP, "狂喜", 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("词汇集");
        verify(sessionMapper, never()).updateEmotion(any(Long.class), anyString());
        verify(emotionLogMapper, never()).insertLog(any());
    }

    @Test
    void 人格自定义词汇集_默认集外人格集内放行() {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("猫娘");
        persona.setDefinition("{\"emotionConfig\": {\"defaultLabel\": \"平静\","
                + " \"vocabularies\": [\"平静\", \"炸毛\"]}}");
        when(personaResolver.resolve(anyString(), anyString(), anyString())).thenReturn(persona);
        storedEmotion(new EmotionState("平静", 50, null,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));

        assertThatThrownBy(() -> service.applySelfReport(GROUP, "狂喜", 10, null))
                .hasMessageContaining("词汇集");
        boolean applied = service.applySelfReport(GROUP, "炸毛", 10, "被叫呆子");
        assertThat(applied).isTrue();
    }

    @Test
    void 管理面重置回基线() {
        storedEmotion(new EmotionState("委屈", 80, "被怼了",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), "平静"));

        service.resetToBaseline(GROUP);

        ArgumentCaptor<String> emotion = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateEmotion(eq(7L), emotion.capture());
        assertThat(emotion.getValue()).contains("\"label\":\"平静\"");
    }
}
