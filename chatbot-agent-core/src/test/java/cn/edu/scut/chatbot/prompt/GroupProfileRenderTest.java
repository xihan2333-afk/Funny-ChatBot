// 守护纪律：群上下文成员概况来自群体画像（粗粒度、不随发言人变化——逐人态度走
// 现注区态度行，两个注入点职责分立）、画像数据按不可信定界注入、无画像回暂无
package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.memory.profile.GroupProfileDO;
import cn.edu.scut.chatbot.memory.profile.GroupProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupProfileRenderTest {

    private GroupMapper groupMapper;
    private GroupConfigMapper groupConfigMapper;
    private GroupProfileMapper groupProfileMapper;
    private PromptAssembler assembler;

    @BeforeEach
    void setUp() {
        cn.edu.scut.chatbot.prompt.PromptFragmentMapper fragmentMapper =
                mock(cn.edu.scut.chatbot.prompt.PromptFragmentMapper.class);
        groupMapper = mock(GroupMapper.class);
        groupConfigMapper = mock(GroupConfigMapper.class);
        cn.edu.scut.chatbot.memory.persona.PersonaResolver personaResolver =
                mock(cn.edu.scut.chatbot.memory.persona.PersonaResolver.class);
        cn.edu.scut.chatbot.prompt.SkillDirectoryRenderer skillDirectoryRenderer =
                mock(cn.edu.scut.chatbot.prompt.SkillDirectoryRenderer.class);
        groupProfileMapper = mock(GroupProfileMapper.class);
        assembler = new PromptAssembler(fragmentMapper, groupMapper, groupConfigMapper,
                personaResolver, skillDirectoryRenderer, groupProfileMapper);

        // 片段层为空（单测聚焦群上下文层）；群行与配置行给最小数据
        when(fragmentMapper.selectPublishedByLayer(anyString())).thenReturn(List.of());
        GroupDO group = new GroupDO();
        group.setGroupName("测试群");
        when(groupMapper.selectByGroup("qq", "10000", "80001")).thenReturn(group);
        when(groupConfigMapper.selectByGroup("qq", "10000", "80001"))
                .thenReturn(new GroupConfigDO());
        when(personaResolver.resolve(anyString(), anyString(), anyString())).thenReturn(null);
    }

    private String assemble() {
        return assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null);
    }

    @Test
    void 成员概况来自群体画像_活跃成员与常聊话题按计数降序() {
        GroupProfileDO profile = new GroupProfileDO();
        profile.setActiveMembers("{\"u2\":30,\"u1\":50,\"u3\":10}");
        profile.setTopicDist("{\"装修\":5,\"原神\":8,\"加班\":2}");
        when(groupProfileMapper.selectByGroup("qq", "10000", "80001")).thenReturn(profile);

        String assembled = assemble();

        // 活跃成员取前五（降序 u1、u2）、话题取前三（降序 原神、装修）——粗粒度一行
        assertThat(assembled).contains("成员概况：常在群里的有 u1、u2");
        assertThat(assembled).contains("最近常聊 原神、装修");
    }

    @Test
    void 无画像行_回暂无文案不炸组装() {
        when(groupProfileMapper.selectByGroup("qq", "10000", "80001")).thenReturn(null);

        assertThat(assemble()).contains("成员概况：暂无（画像尚未生成）");
    }

    @Test
    void 画像计数JSON非法_坏数据视同空_回尚未生成不杀组装() {
        GroupProfileDO profile = new GroupProfileDO();
        profile.setActiveMembers("这不是合法的计数 JSON");
        when(groupProfileMapper.selectByGroup("qq", "10000", "80001")).thenReturn(profile);

        assertThat(assemble()).contains("成员概况：暂无（画像尚未生成）");
    }

    @Test
    void 粗粒度语义_输出不含逐人态度措辞_与定界声明同块() {
        GroupProfileDO profile = new GroupProfileDO();
        profile.setActiveMembers("{\"u1\":50}");
        profile.setTopicDist("{\"装修\":5}");
        when(groupProfileMapper.selectByGroup("qq", "10000", "80001")).thenReturn(profile);

        String assembled = assemble();

        // 群上下文层只有概况，没有档位/态度措辞（那归现注区态度行）；
        // 整块以 XML 定界（US7 不可信变量通道）
        assertThat(assembled).doesNotContain("挚友").doesNotContain("厌恶").doesNotContain("态度");
        int headerIndex = assembled.indexOf("<untrusted src=\"variable\">");
        int digestIndex = assembled.indexOf("成员概况");
        assertThat(headerIndex).isGreaterThanOrEqualTo(0).isLessThan(digestIndex);
    }
}
