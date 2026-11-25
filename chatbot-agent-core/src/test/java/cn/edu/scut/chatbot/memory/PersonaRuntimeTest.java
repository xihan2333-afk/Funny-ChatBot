// 守护纪律：definition 字段缺失回默认不整体失败（人格缺字段不杀 Run）、
// 变体集为空"不用变体"默认项兜底、V4 种子兼容（0~1 健谈度放大、口头禅转变体）、
// 注意力偏好数值与变体权重不进 systemPrompt 的数据面分离
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaRuntimeTest {

    private static PersonaDO persona(String definition) {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("小趣");
        persona.setDefinition(definition);
        return persona;
    }

    @Test
    void definition整体非法JSON_回全默认_人格名保留() {
        PersonaRuntime runtime = PersonaRuntime.parse(persona("这不是合法的 JSON"));

        assertThat(runtime.name()).isEqualTo("小趣");
        assertThat(runtime.selfSettingText()).isEmpty();
        assertThat(runtime.variants()).containsExactly(PersonaRuntime.NO_VARIANT);
        assertThat(runtime.attention().talkativeness()).isEqualTo(50);
        assertThat(runtime.emotion().baseLabel()).isEqualTo("平静");
        assertThat(runtime.emotion().sensitivity()).isEqualTo(1.0);
    }

    @Test
    void definition为null_回全默认() {
        PersonaRuntime runtime = PersonaRuntime.parse(persona(null));

        assertThat(runtime.personaId()).isEqualTo(1L);
        assertThat(runtime.variants()).containsExactly(PersonaRuntime.NO_VARIANT);
    }

    @Test
    void 七字段组部分缺失_缺失字段回默认_其余正常解析() {
        PersonaRuntime runtime = PersonaRuntime.parse(persona("""
                {"selfSetting": "爱玩梗的伙伴",
                 "personalityTraits": {"text": "好奇随和"},
                 "boundaryExamples": ["不开地狱玩笑"]}"""));

        assertThat(runtime.selfSettingText()).isEqualTo("爱玩梗的伙伴");
        assertThat(runtime.personalityText()).isEqualTo("好奇随和");
        assertThat(runtime.forbiddenPatterns()).containsExactly("不开地狱玩笑");
        // 缺失字段回默认
        assertThat(runtime.examples()).isEmpty();
        assertThat(runtime.attention().talkativeness()).isEqualTo(50);
        assertThat(runtime.emotion().vocabularies()).isEmpty();
        assertThat(runtime.groupChatConstraintText()).isEmpty();
    }

    @Test
    void V4种子兼容_小数健谈度放大到百分位_口头禅转变体() {
        PersonaRuntime runtime = PersonaRuntime.parse(persona("""
                {"selfSetting": "爱玩梗的伙伴",
                 "personalityTraits": {"text": "好奇", "catchphrases": ["诶嘿", "有一说一"]},
                 "attentionPreference": {"talkativity": 0.4, "topicSensitivity": ["技术", "游戏"],
                                          "proactivity": 0.3, "interjectionTendency": 0.35},
                 "emotionConfig": {"defaultLabel": "平静"}}"""));

        assertThat(runtime.attention().talkativeness()).isEqualTo(40);
        assertThat(runtime.attention().proactiveness()).isEqualTo(30);
        assertThat(runtime.attention().interjectionTendency()).isEqualTo(35);
        assertThat(runtime.attention().likedTopics()).containsExactly("技术", "游戏");
        assertThat(runtime.emotion().baseLabel()).isEqualTo("平静");
        // 口头禅按每条权重 1 转成变体 + "不用变体"默认项参与
        assertThat(runtime.variants()).hasSize(3);
        assertThat(runtime.variants()).anySatisfy(variant -> {
            assertThat(variant.variantId()).isEqualTo("catchphrase:诶嘿");
            assertThat(variant.text()).isEqualTo("诶嘿");
            assertThat(variant.weight()).isEqualTo(1);
        });
        assertThat(runtime.variants()).contains(PersonaRuntime.NO_VARIANT);
    }

    @Test
    void 完整变体集与情绪配置_字段直译() {
        PersonaRuntime runtime = PersonaRuntime.parse(persona("""
                {"personalityTraits": {"variants": [
                    {"variantId": "v-kek", "text": "吐槽役模式", "weight": 3},
                    {"variantId": "v-moe", "text": "软萌模式", "weight": 1}]},
                 "examples": [{"scenario": "被夸", "bad": "谢谢夸奖", "good": "诶嘿嘿"}],
                 "emotionConfig": {"defaultLabel": "好奇", "vocabularies": ["开心", "炸毛"],
                                    "sensitivity": 0.5, "semanticCues": ["被叫呆子会炸毛"],
                                    "structuredEventOverrides": {"poked": -2}}}"""));

        assertThat(runtime.variants()).extracting(PersonaRuntime.Variant::variantId)
                .containsExactly("v-kek", "v-moe", PersonaRuntime.NO_VARIANT_ID);
        assertThat(runtime.examples()).hasSize(1);
        assertThat(runtime.examples().get(0).goodExample()).isEqualTo("诶嘿嘿");
        assertThat(runtime.emotion().baseLabel()).isEqualTo("好奇");
        assertThat(runtime.emotion().vocabularies()).containsExactly("开心", "炸毛");
        assertThat(runtime.emotion().sensitivity()).isEqualTo(0.5);
        assertThat(runtime.emotion().semanticCues()).containsExactly("被叫呆子会炸毛");
        assertThat(runtime.emotion().structuredEventOverrides()).containsEntry("poked", -2);
    }

    @Test
    void 健谈度越界值_钳制到0到100() {
        PersonaRuntime runtime = PersonaRuntime.parse(persona(
                "{\"attentionPreference\": {\"talkativity\": 150}}"));

        assertThat(runtime.attention().talkativeness()).isEqualTo(100);
    }
}
