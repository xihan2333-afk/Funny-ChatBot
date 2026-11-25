package cn.edu.scut.chatbot.memory.persona;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.context.window.TokenEstimator;
import cn.edu.scut.chatbot.memory.emotion.EmotionService;
import cn.edu.scut.chatbot.memory.emotion.EmotionState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 人格偏好摘要渲染（T026 回补——US6 就绪后执行，contracts/social-and-supply.md §2）：
 * 「渲染」不是「生成」——从 PersonaRuntime.attention 与当前情绪拼几行紧凑文本，
 * 零模型调用（llm_call_log 无新增——负向断言口径）、≤300 token、每次评估现渲染
 * （不缓存不落转录）。
 *
 * <p>消费方：L1 评估与时机评估（{{persona.preferenceDigest}} 变量）。
 * 注意力数值只经本摘要进评估管线，不进 systemPrompt（数值给代码执行不给模型表演）。
 */
@Component
public class PersonaPreferenceDigestRenderer {

    public static final int TOKEN_BUDGET = 300;

    private final PersonaResolver personaResolver;
    private final EmotionService emotionService;

    public PersonaPreferenceDigestRenderer(PersonaResolver personaResolver,
                                           EmotionService emotionService) {
        this.personaResolver = personaResolver;
        this.emotionService = emotionService;
    }

    /**
     * 现渲染当前人格的偏好摘要（健谈度/话题偏好/主动性几行 + 当前情绪一行）
     */
    public String render(GroupKey group) {
        PersonaRuntime runtime = PersonaRuntime.parse(
                personaResolver.resolve(group.platform(), group.botId(), group.groupId()));
        List<String> lines = new ArrayList<>();
        lines.add("健谈度 " + runtime.attention().talkativeness()
                + "，主动性 " + runtime.attention().proactiveness()
                + "，插话倾向 " + runtime.attention().interjectionTendency());
        if (!runtime.attention().likedTopics().isEmpty()) {
            lines.add("话题偏好：" + String.join("、", runtime.attention().likedTopics()));
        }
        if (!runtime.attention().avoidedTopics().isEmpty()) {
            lines.add("话题回避：" + String.join("、", runtime.attention().avoidedTopics()));
        }
        EmotionState emotion = emotionService.read(group);
        lines.add("当前情绪：" + emotion.label() + "（强度 " + emotion.intensity() + "）");

        StringBuilder digest = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            int cost = TokenEstimator.estimate(line);
            if (used + cost > TOKEN_BUDGET) {
                break;   // 无界供给纪律：300 token 硬上限
            }
            if (digest.length() > 0) {
                digest.append('\n');
            }
            digest.append(line);
            used += cost;
        }
        return digest.toString();
    }
}
