// 守护纪律：变体加权抽取代码执行不给模型表演——权重归一、"不用变体"默认项参与加权、
// 固定随机种可测（同 seed 同结果 = Run 内固定，不同 seed 重抽 = Run 间重抽）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaRuntime;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class VariantDrawTest {

    /**
     * 权重 3:1:4 三变体（"不用变体"显式配置占 4）
     */
    private static PersonaRuntime weighted() {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("测试人格");
        persona.setDefinition("""
                {"personalityTraits": {"variants": [
                    {"variantId": "a", "text": "变体A", "weight": 3},
                    {"variantId": "b", "text": "变体B", "weight": 1},
                    {"variantId": "none", "text": "", "weight": 4}]}}""");
        return PersonaRuntime.parse(persona);
    }

    @Test
    void 固定随机种_权重三比一比四_样本分布跟随权重() {
        PersonaRuntime runtime = weighted();
        Map<String, Integer> counts = new HashMap<>();
        int samples = 30_000;
        Random random = new Random(42);
        for (int i = 0; i < samples; i++) {
            counts.merge(runtime.drawVariant(random).variantId(), 1, Integer::sum);
        }

        // 期望占比 a=3/8=37.5%、b=1/8=12.5%、none=4/8=50%（±2% 容差）
        assertThat(counts.get("a") * 100.0 / samples).isBetween(35.5, 39.5);
        assertThat(counts.get("b") * 100.0 / samples).isBetween(10.5, 14.5);
        assertThat(counts.get(PersonaRuntime.NO_VARIANT_ID) * 100.0 / samples)
                .isBetween(48.0, 52.0);
    }

    @Test
    void 同seed同结果_Run内固定() {
        PersonaRuntime runtime = weighted();

        PersonaRuntime.VariantDraw first = runtime.drawVariant(new Random(7));
        PersonaRuntime.VariantDraw replay = runtime.drawVariant(new Random(7));

        // Run 内固定：同 seed 回放抽中同一个变体（VariantDraw 随 Run 快照的确定性基础）
        assertThat(replay.variantId()).isEqualTo(first.variantId());
        assertThat(replay.text()).isEqualTo(first.text());
    }

    @Test
    void 无参形态_每次调用是新抽取_结果永远在变体集内() {
        PersonaRuntime runtime = weighted();

        for (int i = 0; i < 100; i++) {
            PersonaRuntime.VariantDraw draw = runtime.drawVariant();
            assertThat(java.util.List.of("a", "b", PersonaRuntime.NO_VARIANT_ID))
                    .contains(draw.variantId());
        }
    }

    @Test
    void 变体集为空_永远抽中不用变体() {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setDefinition("{\"selfSetting\": \"无变体人格\"}");
        PersonaRuntime runtime = PersonaRuntime.parse(persona);

        PersonaRuntime.VariantDraw draw = runtime.drawVariant(new Random(1));

        assertThat(draw.noVariant()).isTrue();
        assertThat(draw.text()).isEmpty();
    }

    @Test
    void 权重全零_回不用变体兜底() {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setDefinition("""
                {"personalityTraits": {"variants": [
                    {"variantId": "x", "text": "零权重", "weight": 0}]}}""");
        PersonaRuntime runtime = PersonaRuntime.parse(persona);

        // 零权重变体被解析丢弃 → 只剩"不用变体"默认项
        assertThat(runtime.drawVariant(new Random(3)).noVariant()).isTrue();
    }
}
