// 守护纪律：抽中变体文本渲染进 systemPrompt、权重与其余变体不出现（负向）、
// 语义雷点素材进身份层（供 updateEmotion 自报判断）、注意力偏好数值不进 systemPrompt（负向）、
// 人格缺失回默认组装（Run 不因人格缺失失败）
package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.memory.persona.PersonaRuntime;
import cn.edu.scut.chatbot.memory.profile.GroupProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonaAssemblyTest {

    private GroupMapper groupMapper;
    private GroupConfigMapper groupConfigMapper;
    private PersonaResolver personaResolver;
    private FixedDrawAssembler assembler;

    /**
     * 覆写抽取点的确定性组装器（变体抽取随机性的测试缝）
     */
    static class FixedDrawAssembler extends PromptAssembler {

        PersonaRuntime.VariantDraw fixedDraw =
                new PersonaRuntime.VariantDraw(PersonaRuntime.NO_VARIANT_ID, "");

        FixedDrawAssembler(PromptFragmentMapper fragmentMapper, GroupMapper groupMapper,
                           GroupConfigMapper groupConfigMapper, PersonaResolver personaResolver,
                           SkillDirectoryRenderer skillRenderer, GroupProfileMapper profileMapper) {
            super(fragmentMapper, groupMapper, groupConfigMapper, personaResolver,
                    skillRenderer, profileMapper);
        }

        @Override
        PersonaRuntime.VariantDraw drawVariantOf(PersonaRuntime runtime) {
            return fixedDraw;
        }
    }

    @BeforeEach
    void setUp() {
        PromptFragmentMapper fragmentMapper = mock(PromptFragmentMapper.class);
        groupMapper = mock(GroupMapper.class);
        groupConfigMapper = mock(GroupConfigMapper.class);
        personaResolver = mock(PersonaResolver.class);
        SkillDirectoryRenderer skillRenderer = mock(SkillDirectoryRenderer.class);
        GroupProfileMapper groupProfileMapper = mock(GroupProfileMapper.class);
        assembler = new FixedDrawAssembler(fragmentMapper, groupMapper, groupConfigMapper,
                personaResolver, skillRenderer, groupProfileMapper);

        when(fragmentMapper.selectPublishedByLayer(anyString())).thenReturn(List.of());
        GroupDO group = new GroupDO();
        group.setGroupName("测试群");
        when(groupMapper.selectByGroup("qq", "10000", "80001")).thenReturn(group);
        when(groupConfigMapper.selectByGroup("qq", "10000", "80001"))
                .thenReturn(new GroupConfigDO());
    }

    private PersonaDO personaOf(String definition) {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("小趣");
        persona.setDefinition(definition);
        return persona;
    }

    private String assemble() {
        return assembler.assemble(RunConfig.ExecutionForm.TURN, "qq", "10000", "80001", null);
    }

    @Test
    void 抽中变体文本进systemPrompt_权重与其余变体不出现() {
        when(personaResolver.resolve("qq", "10000", "80001")).thenReturn(personaOf("""
                {"selfSetting": "爱玩梗的伙伴",
                 "personalityTraits": {"variants": [
                    {"variantId": "v-kek", "text": "吐槽役模式：阴阳怪气接梗", "weight": 3},
                    {"variantId": "v-moe", "text": "软萌模式：颜文字收尾", "weight": 1}]}}"""));
        assembler.fixedDraw = new PersonaRuntime.VariantDraw("v-kek", "吐槽役模式：阴阳怪气接梗");

        String assembled = assemble();

        assertThat(assembled).contains("[当前表达变体]");
        assertThat(assembled).contains("吐槽役模式：阴阳怪气接梗");
        // 负向：其余变体与权重数值绝不出现
        assertThat(assembled).doesNotContain("软萌模式");
        assertThat(assembled).doesNotContain("weight").doesNotContain("权重");
    }

    @Test
    void 抽中不用变体_身份层无变体块() {
        when(personaResolver.resolve("qq", "10000", "80001")).thenReturn(personaOf("""
                {"personalityTraits": {"variants": [
                    {"variantId": "v-kek", "text": "吐槽役模式", "weight": 3}]}}"""));
        assembler.fixedDraw = new PersonaRuntime.VariantDraw(PersonaRuntime.NO_VARIANT_ID, "");

        assertThat(assemble()).doesNotContain("[当前表达变体]");
    }

    @Test
    void 语义雷点与回弹素材进身份层() {
        when(personaResolver.resolve("qq", "10000", "80001")).thenReturn(personaOf(
                "{\"emotionConfig\": {\"semanticCues\": [\"被叫呆子会炸毛\","
                        + "\"对方真心给台阶才回弹\"]}}"));

        String assembled = assemble();

        assertThat(assembled).contains("情绪雷点与回弹素材");
        assertThat(assembled).contains("被叫呆子会炸毛");
        assertThat(assembled).contains("对方真心给台阶才回弹");
    }

    @Test
    void 注意力偏好数值不进systemPrompt_负向断言() {
        when(personaResolver.resolve("qq", "10000", "80001")).thenReturn(personaOf("""
                {"attentionPreference": {"talkativity": 0.4, "proactivity": 0.3,
                                          "interjectionTendency": 0.35,
                                          "topicSensitivity": ["技术"]}}"""));

        String assembled = assemble();

        // 数值与参数名都不得出现（它是给评估管线的数值不是给模型看的自我描述）
        assertThat(assembled).doesNotContain("talkativity").doesNotContain("健谈度");
        assertThat(assembled).doesNotContain("proactivity").doesNotContain("主动性");
        assertThat(assembled).doesNotContain("40").doesNotContain("30").doesNotContain("35");
    }

    @Test
    void 人格解析失败_回默认组装_Run不因人格缺失失败() {
        when(personaResolver.resolve("qq", "10000", "80001"))
                .thenThrow(new RuntimeException("persona 表不可读"));

        String assembled = assemble();

        assertThat(assembled).contains("群上下文");   // 其余层照常组装
        assertThat(assembled).doesNotContain("[当前表达变体]");
    }
}
