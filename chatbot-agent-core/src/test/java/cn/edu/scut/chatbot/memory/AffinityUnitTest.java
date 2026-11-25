// 守护纪律：档位边界 ±30/±70 八值精确归属（4.2.7 必测——L1 档位调制断言在 T027 完成后回补）、
// 活跃度分档与氛围基线阈值、称呼回退链
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.memory.profile.AffinityService;
import cn.edu.scut.chatbot.memory.profile.AttitudeNoteRenderer;
import cn.edu.scut.chatbot.memory.profile.DailyProfileStatistics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AffinityUnitTest {

    @Test
    void 档位边界八值_精确归属() {
        assertThat(AffinityService.tierOf(29)).isEqualTo(AffinityService.Tier.NEUTRAL);
        assertThat(AffinityService.tierOf(30)).isEqualTo(AffinityService.Tier.FRIEND);
        assertThat(AffinityService.tierOf(69)).isEqualTo(AffinityService.Tier.FRIEND);
        assertThat(AffinityService.tierOf(70)).isEqualTo(AffinityService.Tier.BEST_FRIEND);
        assertThat(AffinityService.tierOf(-29)).isEqualTo(AffinityService.Tier.NEUTRAL);
        assertThat(AffinityService.tierOf(-30)).isEqualTo(AffinityService.Tier.DISTANT);
        assertThat(AffinityService.tierOf(-69)).isEqualTo(AffinityService.Tier.DISTANT);
        assertThat(AffinityService.tierOf(-70)).isEqualTo(AffinityService.Tier.HOSTILE);
    }

    @Test
    void 档位标签与显著性_无感不标注() {
        assertThat(AffinityService.Tier.BEST_FRIEND.label()).isEqualTo("挚友");
        assertThat(AffinityService.Tier.FRIEND.label()).isEqualTo("朋友");
        assertThat(AffinityService.Tier.NEUTRAL.label()).isEqualTo("无感");
        assertThat(AffinityService.Tier.DISTANT.label()).isEqualTo("疏远");
        assertThat(AffinityService.Tier.HOSTILE.label()).isEqualTo("厌恶");
        assertThat(AffinityService.Tier.NEUTRAL.significant()).isFalse();
        assertThat(AffinityService.Tier.FRIEND.significant()).isTrue();
    }

    @Test
    void 极值越界_档位稳定() {
        assertThat(AffinityService.tierOf(100)).isEqualTo(AffinityService.Tier.BEST_FRIEND);
        assertThat(AffinityService.tierOf(-100)).isEqualTo(AffinityService.Tier.HOSTILE);
        assertThat(AffinityService.tierOf(0)).isEqualTo(AffinityService.Tier.NEUTRAL);
    }

    @Test
    void 活跃度与氛围阈值分界() {
        assertThat(DailyProfileStatistics.activityLevelOf(20)).isEqualTo("活跃");
        assertThat(DailyProfileStatistics.activityLevelOf(19)).isEqualTo("一般");
        assertThat(DailyProfileStatistics.activityLevelOf(5)).isEqualTo("一般");
        assertThat(DailyProfileStatistics.activityLevelOf(4)).isEqualTo("安静");
        assertThat(DailyProfileStatistics.vibeOf(200)).isEqualTo("热闹");
        assertThat(DailyProfileStatistics.vibeOf(50)).isEqualTo("一般");
        assertThat(DailyProfileStatistics.vibeOf(49)).isEqualTo("安静");
    }

    @Test
    void 称呼回退链_callName优先_platformName次之_userId兜底() {
        assertThat(AttitudeNoteRenderer.displayName(
                new AffinityService.AttitudeView(80, AffinityService.Tier.BEST_FRIEND,
                        70, "三哥", "张三", null), "u1"))
                .isEqualTo("三哥");
        assertThat(AttitudeNoteRenderer.displayName(
                new AffinityService.AttitudeView(80, AffinityService.Tier.BEST_FRIEND,
                        70, null, "张三", null), "u1"))
                .isEqualTo("张三");
        assertThat(AttitudeNoteRenderer.displayName(
                new AffinityService.AttitudeView(80, AffinityService.Tier.BEST_FRIEND,
                        70, null, null, null), "u9"))
                .isEqualTo("u9");
    }
}
