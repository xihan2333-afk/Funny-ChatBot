// 守护纪律：切换改绑下个 Run 生效（快照纪律）、记忆画像群状态不随切换清空（结构性
// 不触碰记忆与画像表——换人格不丢记忆）、情绪重置为新人格基线、目标人格缺失拒绝
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SwitchPersonaTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "99301");

    private AgentSessionMapper sessionMapper;
    private PersonaMapper personaMapper;
    private PersonaResolver resolver;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AgentSessionMapper.class);
        personaMapper = mock(PersonaMapper.class);
        resolver = new PersonaResolver(personaMapper, sessionMapper);
    }

    private PersonaDO persona(long id, String name, String definition) {
        PersonaDO persona = new PersonaDO();
        persona.setId(id);
        persona.setName(name);
        persona.setDefinition(definition);
        return persona;
    }

    @Test
    void 切换改绑persona_id_情绪重置为新人格基线() {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(7L);
        when(sessionMapper.selectByGroup("qq", "10000", "99301")).thenReturn(session);
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(
                persona(1L, "小趣", "{\"emotionConfig\": {\"defaultLabel\": \"平静\"}}"),
                persona(2L, "猫娘", "{\"emotionConfig\": {\"defaultLabel\": \"好奇\"}}")));

        resolver.switchPersona(GROUP, 2L);

        verify(sessionMapper).updatePersona(7L, 2L);
        ArgumentCaptor<String> emotion = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateEmotion(anyLong(), emotion.capture());
        assertThat(emotion.getValue()).contains("\"label\":\"好奇\"").contains("50");
    }

    @Test
    void 目标人格不存在_拒绝且不改绑() {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(7L);
        when(sessionMapper.selectByGroup("qq", "10000", "99301")).thenReturn(session);
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(persona(1L, "小趣", "{}")));

        assertThatThrownBy(() -> resolver.switchPersona(GROUP, 99L))
                .hasMessageContaining("99");
        verify(sessionMapper, org.mockito.Mockito.never()).updatePersona(anyLong(), anyLong());
    }

    @Test
    void 会话不存在_拒绝() {
        when(sessionMapper.selectByGroup("qq", "10000", "99301")).thenReturn(null);

        assertThatThrownBy(() -> resolver.switchPersona(GROUP, 1L))
                .hasMessageContaining("会话不存在");
    }

    @Test
    void 切换后resolve立即返回新人格_下个Run生效() {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(7L);
        session.setPersonaId(2L);
        when(sessionMapper.selectByGroup("qq", "10000", "99301")).thenReturn(session);
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(
                persona(2L, "猫娘", "{\"selfSetting\": \"喵\"}")));

        PersonaDO resolved = resolver.resolve("qq", "10000", "99301");

        assertThat(resolved.getName()).isEqualTo("猫娘");
    }

    @Test
    void 基线缺失的定义_情绪重置回内置默认平静() {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(7L);
        when(sessionMapper.selectByGroup("qq", "10000", "99301")).thenReturn(session);
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(
                persona(1L, "小趣", "{}"),
                persona(3L, "朴直人格", "{\"emotionConfig\": {}}")));

        resolver.switchPersona(GROUP, 3L);

        ArgumentCaptor<String> emotion = ArgumentCaptor.forClass(String.class);
        verify(sessionMapper).updateEmotion(anyLong(), emotion.capture());
        assertThat(emotion.getValue()).contains("\"label\":\"平静\"");
    }
}
