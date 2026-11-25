// 守护纪律：主动参数全量 system_param 热变更、活跃时段取群体画像、强负面预算减半与阈值提高（T052）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.memory.emotion.EmotionService;
import cn.edu.scut.chatbot.memory.emotion.EmotionState;
import cn.edu.scut.chatbot.memory.profile.GroupProfileDO;
import cn.edu.scut.chatbot.memory.profile.GroupProfileMapper;
import cn.edu.scut.chatbot.memory.social.ProactivePolicy;
import cn.edu.scut.chatbot.memory.social.ProactivePolicyImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T052：ProactivePolicy——参数供给与情绪微调。间隔 / 预算的消费行为归 ProactiveSchedulerTest。
 */
class ProactivePolicyTest {

    private SystemParamService systemParams;
    private GroupProfileMapper groupProfileMapper;
    private EmotionService emotionService;
    private ProactivePolicy policy;
    private final GroupKey group = new GroupKey("qq", "bot1", "g1");

    @BeforeEach
    void setUp() {
        systemParams = mock(SystemParamService.class);
        groupProfileMapper = mock(GroupProfileMapper.class);
        emotionService = mock(EmotionService.class);
        when(systemParams.getString(anyString(), any())).thenReturn(null);
        when(emotionService.read(any())).thenReturn(null);
        policy = new ProactivePolicyImpl(systemParams, groupProfileMapper, emotionService);
    }

    @Test
    void 缺省参数_三十分钟两小时十条与默认活跃时段() {
        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertEquals(30, params.silenceBreakMinutes());
        assertEquals(2, params.minIntervalHours());
        assertEquals(10, params.dailyBudget());
        assertEquals(ProactivePolicy.ProactiveParams.defaultActiveHours(), params.activeHours());
    }

    @Test
    void system_param热变更_三参数生效() {
        when(systemParams.getString(anyString(), any())).thenReturn(
                "{\"silenceBreakMinutes\":45, \"minIntervalHours\":3, \"dailyBudget\":6}");
        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertEquals(45, params.silenceBreakMinutes());
        assertEquals(3, params.minIntervalHours());
        assertEquals(6, params.dailyBudget());
    }

    @Test
    void 活跃时段取群体画像_偶发凌晨时段不构成活跃() {
        // 峰值 50 条在 14/20/21 点，凌晨 3 点偶发 1 条 → 均值显著之下被排除
        GroupProfileDO profile = new GroupProfileDO();
        profile.setActiveHours("{\"3\":1, \"14\":50, \"20\":40, \"21\":30}");
        when(groupProfileMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(profile);

        Set<Integer> hours = policy.effectiveParams(group).activeHours();
        assertFalse(hours.contains(3), "凌晨偶发一条不构成活跃时段");
        assertTrue(hours.contains(14));
        assertTrue(hours.contains(20));

        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertTrue(params.isActiveHour(LocalDateTime.parse("2026-11-25T14:30:00".replace('T', 'T'))));
        assertFalse(params.isActiveHour(LocalDateTime.parse("2026-11-25T03:00:00")),
                "凌晨 3 点（活跃时段外）不产生破冰触发");
    }

    @Test
    void 无画像回退默认时段_白天可触发凌晨不触发() {
        when(groupProfileMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(null);
        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertTrue(params.isActiveHour(LocalDateTime.parse("2026-11-25T15:00:00")));
        assertFalse(params.isActiveHour(LocalDateTime.parse("2026-11-25T03:00:00")));
    }

    @Test
    void 强负面情绪_预算减半且破冰阈值提高() {
        when(emotionService.read(group)).thenReturn(new EmotionState(
                "不爽", 80, "被群友怼了", null, "平静"));
        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertEquals(5, params.dailyBudget(), "10 条预算强负面减半为 5");
        assertEquals(45, params.silenceBreakMinutes(), "30 分钟阈值提高 1.5 倍为 45");
    }

    @Test
    void 负面但强度不足七十_不触发微调() {
        when(emotionService.read(group)).thenReturn(new EmotionState(
                "不爽", 69, "有点烦", null, "平静"));
        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertEquals(10, params.dailyBudget());
        assertEquals(30, params.silenceBreakMinutes());
    }

    @Test
    void 正面强情绪_不触发微调() {
        when(emotionService.read(group)).thenReturn(new EmotionState(
                "开心", 90, null, null, "平静"));
        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertEquals(10, params.dailyBudget());
        assertEquals(30, params.silenceBreakMinutes());
    }

    @Test
    void 情绪读取失败_不拦策略走基础参数() {
        when(emotionService.read(any())).thenThrow(new RuntimeException("session missing"));
        ProactivePolicy.ProactiveParams params = policy.effectiveParams(group);
        assertEquals(10, params.dailyBudget());
        assertEquals(30, params.silenceBreakMinutes());
    }
}
