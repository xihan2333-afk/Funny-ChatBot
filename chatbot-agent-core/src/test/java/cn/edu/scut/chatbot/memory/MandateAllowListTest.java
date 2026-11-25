// 守护纪律：自主行为类别须在 mandate 允许清单内、清单变更触发存量重验证（T056，既有 MandateGuard 机制消费）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.task.MandateGuard;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T056：Mandate 允许清单运营面数据落位——group_config 的自主行为允许清单
 * （tasks.md 所称 mandate_auto 语义，既有列 allowed_autonomous_categories）。
 * 不新建机制：消费既有 MandateGuard 的创建校验与存量重验证。
 */
class MandateAllowListTest {

    private GroupConfigMapper groupConfigMapper;
    private MandateGuard mandateGuard;

    @BeforeEach
    void setUp() {
        groupConfigMapper = mock(GroupConfigMapper.class);
        mandateGuard = new MandateGuard(groupConfigMapper);
    }

    private GroupConfigDO config(String autonomousJson) {
        GroupConfigDO config = new GroupConfigDO();
        config.setPlatform("qq");
        config.setBotId("bot1");
        config.setGroupId("g1");
        config.setAllowedAutonomousCategories(autonomousJson);
        config.setAllowedMemberTaskCategories("[]");
        return config;
    }

    private TaskDO autonomousTask(String category) {
        TaskDO task = new TaskDO();
        task.setPlatform("qq");
        task.setBotId("bot1");
        task.setGroupId("g1");
        task.setCategory(category);
        task.setMandateSource(TaskDO.MANDATE_AUTONOMOUS);
        return task;
    }

    @Test
    void 清单外类别_Task创建即失格() {
        when(groupConfigMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(config("[\"每日问候\",\"静默破冰\"]"));
        assertThrows(CommandRejectedException.class,
                () -> mandateGuard.assertCreatable(autonomousTask("话题再引入")),
                "清单不含「话题再引入」——该类别自主 Task 创建即被拒");
    }

    @Test
    void 清单内类别_创建放行() {
        when(groupConfigMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(config("[\"每日问候\",\"话题再引入\",\"静默破冰\"]"));
        assertDoesNotThrow(() -> mandateGuard.assertCreatable(autonomousTask("话题再引入")));
    }

    @Test
    void 清单变更后_存量Task重验证失格() {
        // 创建时清单含该类别
        when(groupConfigMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(config("[\"话题再引入\"]"));
        TaskDO task = autonomousTask("话题再引入");
        assertDoesNotThrow(() -> mandateGuard.assertCreatable(task));

        // 运营收窄清单——存量重验证失格（调用方取消进审计）
        when(groupConfigMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(config("[\"每日问候\"]"));
        assertTrue(mandateGuard.isDisqualified(task), "清单变更触发存量重验证——失格待取消");
    }

    @Test
    void 清单变更后仍在清单的存量Task_重验证通过() {
        when(groupConfigMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(config("[\"每日问候\",\"静默破冰\"]"));
        assertFalse(mandateGuard.isDisqualified(autonomousTask("静默破冰")));
    }

    @Test
    void 群配置缺失_宁严勿松拒绝() {
        when(groupConfigMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(null);
        assertThrows(CommandRejectedException.class,
                () -> mandateGuard.assertCreatable(autonomousTask("每日问候")));
    }

    @Test
    void 清单JSON损坏_按空清单宁严勿松() {
        when(groupConfigMapper.selectByGroup(anyString(), anyString(), anyString()))
                .thenReturn(config("{broken"));
        assertThrows(CommandRejectedException.class,
                () -> mandateGuard.assertCreatable(autonomousTask("每日问候")),
                "坏清单按空处理——拒绝一切自主类别");
    }
}
