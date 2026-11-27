package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.RuntimeAdminFacadeImpl;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.outbox.OutboxMapper;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.memory.emotion.EmotionAdminService;
import cn.edu.scut.chatbot.memory.emotion.EmotionService;
import cn.edu.scut.chatbot.memory.emotion.EmotionState;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.security.gatepolicy.CostBreakerService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RuntimeAdminControllerTest {

    @Test
    void extractsTraceIdFromW3cTraceparentAndRejectsRetryOfNonFailedTask() {
        assertThat(RuntimeAdminFacadeImpl.extractTraceId(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

        TaskMapper taskMapper = mock(TaskMapper.class);
        TaskDO runningTask = new TaskDO();
        runningTask.setId(100L);
        runningTask.setStatus("RUNNING");
        when(taskMapper.selectById(100L)).thenReturn(runningTask);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-27T05:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RuntimeAdminFacadeImpl facade = new RuntimeAdminFacadeImpl(
                mock(GroupMapper.class), mock(TurnMapper.class), taskMapper,
                mock(TriggerEventMapper.class), mock(OutboxMapper.class),
                mock(CostBreakerService.class), fixedClock);

        assertThatThrownBy(() -> facade.retryFailedTask(100L, "admin"))
                .isInstanceOf(CommandRejectedException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void emotionAdminListsLazyDecayedIntensityAndResetsToBaseline() {
        GroupMapper groupMapper = mock(GroupMapper.class);
        GroupDO g = new GroupDO();
        g.setPlatform("onebot-v11");
        g.setBotId("10001");
        g.setGroupId("20001");
        g.setGroupName("测试群");
        when(groupMapper.selectAllInService()).thenReturn(List.of(g));

        EmotionService emotionService = mock(EmotionService.class);
        when(emotionService.view(new GroupKey("onebot-v11", "10001", "20001")))
                .thenReturn(new EmotionState("happy", 62, "收到夸奖", LocalDateTime.of(2026, 11, 27, 12, 0), "calm"));

        PersonaResolver resolver = mock(PersonaResolver.class);
        PersonaDO p = new PersonaDO();
        p.setDefinition("{}");
        when(resolver.resolve("onebot-v11", "10001", "20001")).thenReturn(p);

        EmotionAdminService emotionAdmin = new EmotionAdminService(groupMapper, emotionService, resolver);
        var list = emotionAdmin.listGroupEmotions();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).effectiveIntensity()).isEqualTo(62);

        emotionAdmin.resetToBaseline("onebot-v11", "10001", "20001");
        verify(emotionService).resetToBaseline(new GroupKey("onebot-v11", "10001", "20001"));
    }
}
