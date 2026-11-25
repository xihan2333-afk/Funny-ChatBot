// 守护纪律：人格偏好摘要纯渲染零模型调用（渲染器无模型依赖——结构保证，
// IT 层以 llm_call_log 无新增复核）、300 token 上限、值变化下次渲染跟随（不缓存）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.context.window.TokenEstimator;
import cn.edu.scut.chatbot.memory.emotion.EmotionService;
import cn.edu.scut.chatbot.memory.emotion.EmotionState;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaPreferenceDigestRenderer;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonaPreferenceDigestTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "99601");

    private PersonaResolver personaResolver;
    private EmotionService emotionService;
    private PersonaPreferenceDigestRenderer renderer;

    @BeforeEach
    void setUp() {
        personaResolver = mock(PersonaResolver.class);
        emotionService = mock(EmotionService.class);
        renderer = new PersonaPreferenceDigestRenderer(personaResolver, emotionService);
        when(emotionService.read(GROUP)).thenReturn(
                new EmotionState("开心", 70, "被夸了", LocalDateTime.now(), "平静"));
    }

    private void personaOf(String definition) {
        PersonaDO persona = new PersonaDO();
        persona.setId(1L);
        persona.setName("小趣");
        persona.setDefinition(definition);
        when(personaResolver.resolve(anyString(), anyString(), anyString())).thenReturn(persona);
    }

    @Test
    void 渲染含注意力参数与当前情绪_紧凑几行() {
        personaOf("{\"attentionPreference\": {\"talkativity\": 0.8, \"proactivity\": 0.6,"
                + " \"interjectionTendency\": 0.4, \"topicSensitivity\": [\"技术\", \"游戏\"]}}");

        String digest = renderer.render(GROUP);

        assertThat(digest).contains("健谈度 80");
        assertThat(digest).contains("主动性 60");
        assertThat(digest).contains("话题偏好：技术、游戏");
        assertThat(digest).contains("当前情绪：开心（强度 70）");
    }

    @Test
    void 三百token硬上限() {
        StringBuilder topics = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            topics.append("话题").append(i).append("、");
        }
        personaOf("{\"attentionPreference\": {\"talkativity\": 50,"
                + " \"topicSensitivity\": [\"" + topics + "\"]}}");

        String digest = renderer.render(GROUP);

        assertThat(TokenEstimator.estimate(digest)).isLessThanOrEqualTo(300);
    }

    @Test
    void 值变化下次渲染跟随_不缓存() {
        personaOf("{\"attentionPreference\": {\"talkativity\": 0.4}}");
        assertThat(renderer.render(GROUP)).contains("健谈度 40");

        personaOf("{\"attentionPreference\": {\"talkativity\": 0.9}}");
        assertThat(renderer.render(GROUP)).contains("健谈度 90");
    }
}
