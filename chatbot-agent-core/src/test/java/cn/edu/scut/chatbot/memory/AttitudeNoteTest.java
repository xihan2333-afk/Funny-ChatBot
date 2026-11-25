// 守护纪律：态度行注入——显著者各一行、无感者零注入（负向）、上限 5 行 150 token、
// 称呼回退链 call_name → platform_name → userId（contracts/social-and-supply.md §5）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.memory.profile.AffinityService;
import cn.edu.scut.chatbot.memory.profile.AttitudeNoteRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttitudeNoteTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "99001");

    private AffinityService affinityService;
    private AttitudeNoteRenderer renderer;

    @BeforeEach
    void setUp() {
        affinityService = mock(AffinityService.class);
        SystemParamService systemParams = mock(SystemParamService.class);
        when(systemParams.getString(anyString(), any())).thenReturn(null);
        renderer = new AttitudeNoteRenderer(affinityService, systemParams);
    }

    private static AffinityService.AttitudeView view(int affinity, int familiarity,
                                                     String callName, String platformName) {
        return new AffinityService.AttitudeView(affinity,
                AffinityService.tierOf(affinity), familiarity, callName, platformName, null);
    }

    @Test
    void 显著者各注一行_档位措辞与基调短语齐全() {
        when(affinityService.view(GROUP, "u1"))
                .thenReturn(new AffinityService.AttitudeView(80,
                        AffinityService.Tier.BEST_FRIEND, 70, "三哥", "张三", "常一起斗图损嘴"));

        List<String> lines = renderer.render(GROUP, List.of("u1"));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("三哥").contains("挚友").contains("常一起斗图损嘴");
    }

    @Test
    void 无感者零注入_一个字不注() {
        when(affinityService.view(GROUP, "u1"))
                .thenReturn(new AffinityService.AttitudeView(10,
                        AffinityService.Tier.NEUTRAL, 20, null, null, null));
        when(affinityService.view(GROUP, "u2"))
                .thenReturn(new AffinityService.AttitudeView(-10,
                        AffinityService.Tier.NEUTRAL, 30, null, null, null));

        assertThat(renderer.render(GROUP, List.of("u1", "u2"))).isEmpty();
    }

    @Test
    void 边界显著者_好感恰三十与熟悉度恰五十都算显著() {
        when(affinityService.view(GROUP, "u1")).thenReturn(view(30, 0, null, null));
        when(affinityService.view(GROUP, "u2")).thenReturn(view(0, 50, null, null));

        assertThat(renderer.render(GROUP, List.of("u1", "u2"))).hasSize(2);
    }

    @Test
    void 上限五行_超预算即止且总量不超150token() {
        // 六个显著者：只出前五行
        for (int i = 1; i <= 6; i++) {
            when(affinityService.view(GROUP, "u" + i))
                    .thenReturn(view(80, 70, "成员" + i, null));
        }

        List<String> lines = renderer.render(GROUP,
                List.of("u1", "u2", "u3", "u4", "u5", "u6"));

        assertThat(lines).hasSize(5);
        assertThat(lines).allSatisfy(line ->
                assertThat(cn.edu.scut.chatbot.context.window.TokenEstimator.estimate(line))
                        .isLessThanOrEqualTo(150));
    }

    @Test
    void 超长基调短语_单行超预算即不注_硬上限守住() {
        // 第一行的基调短语极长（单行超 150 token）——不注该行并停止；预算是硬上限不是参考值
        String longDigest = "上辈子拯救了银河系这辈子天天陪着聊天还帮着怼过好几个杠精".repeat(10);
        when(affinityService.view(GROUP, "u1"))
                .thenReturn(new AffinityService.AttitudeView(80,
                        AffinityService.tierOf(80), 70, "三哥", null, longDigest));
        when(affinityService.view(GROUP, "u2")).thenReturn(view(80, 70, "李四", null));

        List<String> lines = renderer.render(GROUP, List.of("u1", "u2"));

        assertThat(lines).isEmpty();
    }

    @Test
    void 称呼回退链_学到的称呼优先_平台昵称次之_最后userId() {
        AffinityService.AttitudeView withCallName = view(80, 70, "三哥", "张三");
        AffinityService.AttitudeView withPlatformNameOnly = view(80, 70, null, "张三");
        AffinityService.AttitudeView bare = view(80, 70, null, null);

        assertThat(AttitudeNoteRenderer.displayName(withCallName, "u1")).isEqualTo("三哥");
        assertThat(AttitudeNoteRenderer.displayName(withPlatformNameOnly, "u1")).isEqualTo("张三");
        assertThat(AttitudeNoteRenderer.displayName(bare, "u9")).isEqualTo("u9");
    }
}
