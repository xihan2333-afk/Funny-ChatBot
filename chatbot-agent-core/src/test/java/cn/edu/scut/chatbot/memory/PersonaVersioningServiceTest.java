// 守护纪律：发布 = 主表快照入 content_version + version_no 递增 + ConfigRefreshEvent；
// 回滚 = 旧快照写回主表再走一次发布（历史只增不减）；草稿编辑不影响线上（发布才落版本）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaVersioningService;
import cn.edu.scut.chatbot.tool.registry.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonaVersioningServiceTest {

    private PersonaMapper personaMapper;
    private ApplicationEventPublisher eventPublisher;
    private PersonaVersioningService service;

    @BeforeEach
    void setUp() {
        personaMapper = mock(PersonaMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new PersonaVersioningService(personaMapper, eventPublisher);
    }

    private PersonaDO persona(long id, String definition, Integer versionNo) {
        PersonaDO persona = new PersonaDO();
        persona.setId(id);
        persona.setName("小趣");
        persona.setDefinition(definition);
        persona.setIsDefault(1);
        persona.setStatus("published");
        persona.setVersionNo(versionNo);
        return persona;
    }

    @Test
    void 发布_主表快照入版本表_版本号递增_刷新事件发出() {
        PersonaDO persona = persona(1L, "{\"selfSetting\": \"草稿内容\"}", 0);
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(persona));
        when(personaMapper.selectMaxPersonaVersion(1L)).thenReturn(3);

        service.publishPersona(1L, "admin", "首发");

        verify(personaMapper).insertPersonaVersion(eq(1L), eq(4), eq("{\"selfSetting\": \"草稿内容\"}"));
        ArgumentCaptor<PersonaDO> updated = ArgumentCaptor.forClass(PersonaDO.class);
        verify(personaMapper).updatePersona(updated.capture());
        assertThat(updated.getValue().getVersionNo()).isEqualTo(4);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(ToolRegistry.ConfigRefreshEvent.class);
        assertThat(((ToolRegistry.ConfigRefreshEvent) event.getValue()).reason())
                .contains("persona-published:1");
    }

    @Test
    void 无历史版本_首发版本号为一() {
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(persona(1L, "{}", 0)));
        when(personaMapper.selectMaxPersonaVersion(1L)).thenReturn(null);

        service.publishPersona(1L, "admin", null);

        verify(personaMapper).insertPersonaVersion(eq(1L), eq(1), anyString());
    }

    @Test
    void 回滚_旧快照写回主表_再走一次发布() {
        PersonaDO persona = persona(1L, "{\"selfSetting\": \"跑偏后的内容\"}", 5);
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(persona));
        when(personaMapper.selectPersonaSnapshot(1L, 2))
                .thenReturn("{\"selfSetting\": \"正常内容\"}");
        when(personaMapper.selectMaxPersonaVersion(1L)).thenReturn(5);

        service.rollbackPersona(1L, 2);

        // 写回主表（definition=旧快照）+ 发布（快照再入版本表 version 6）
        ArgumentCaptor<PersonaDO> updated = ArgumentCaptor.forClass(PersonaDO.class);
        verify(personaMapper, times(2)).updatePersona(updated.capture());
        assertThat(updated.getAllValues().get(0).getDefinition())
                .isEqualTo("{\"selfSetting\": \"正常内容\"}");
        verify(personaMapper).insertPersonaVersion(eq(1L), eq(6),
                eq("{\"selfSetting\": \"正常内容\"}"));
    }

    @Test
    void 回滚目标版本不存在_拒绝() {
        when(personaMapper.selectAllPersonas()).thenReturn(List.of(persona(1L, "{}", 1)));
        when(personaMapper.selectPersonaSnapshot(1L, 9)).thenReturn(null);

        assertThatThrownBy(() -> service.rollbackPersona(1L, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9");
        verify(personaMapper, times(0)).insertPersonaVersion(anyLong(), any(Integer.class), any());
    }

    @Test
    void 发布不存在的人格_拒绝() {
        when(personaMapper.selectAllPersonas()).thenReturn(List.of());

        assertThatThrownBy(() -> service.publishPersona(99L, "admin", null))
                .hasMessageContaining("99");
    }
}
