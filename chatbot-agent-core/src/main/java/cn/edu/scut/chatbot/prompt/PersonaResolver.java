package cn.edu.scut.chatbot.prompt;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 人格解析器（T088）：身份层所需字段子集 + 注意力偏好直取（L1 输入）+
 * 表达变体加权抽取（权重归一，Run 启动时定——同 Run 内稳定）。
 */
@Component
public class PersonaResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonaResolver.class);

    private final PromptFragmentMapper fragmentMapper;
    private final AgentSessionMapper sessionMapper;

    public PersonaResolver(PromptFragmentMapper fragmentMapper, AgentSessionMapper sessionMapper) {
        this.fragmentMapper = fragmentMapper;
        this.sessionMapper = sessionMapper;
    }

    /**
     * 解析当前人格：会话绑定优先，回落默认人格（种子 V4 预置）
     */
    public PersonaDO resolve(String platform, String botId, String groupId) {
        if (groupId != null) {
            AgentSessionDO session = sessionMapper.selectByGroup(platform, botId, groupId);
            if (session != null && session.getPersonaId() != null) {
                for (PersonaDO persona : fragmentMapper.selectAllPersonas()) {
                    if (session.getPersonaId().equals(persona.getId())) {
                        return persona;
                    }
                }
            }
        }
        return fragmentMapper.selectDefaultPersona();
    }

    /**
     * 身份层素材：自我设定文本（definition 的 selfSetting 字段组）
     */
    public String selfSettingOf(PersonaDO persona) {
        Map<String, Object> definition = parseDefinition(persona);
        Object setting = definition.get("selfSetting");
        return setting == null ? "" : String.valueOf(setting);
    }

    /**
     * 注意力偏好摘要（L1 有界上下文第三路输入——健谈度/话题敏感/主动性/插话倾向）
     */
    public String preferenceText(String platform, String botId, String groupId) {
        PersonaDO persona = resolve(platform, botId, groupId);
        if (persona == null) {
            return "";
        }
        Map<String, Object> definition = parseDefinition(persona);
        Object preference = definition.get("attentionPreference");
        return preference == null ? "" : String.valueOf(preference);
    }

    /**
     * 表达变体加权抽取（口头禅——Run 启动时定一次，Run 内稳定；
     * 第一版均匀随机抽取，2.4 落地权重字段后加权）
     */
    public String pickCatchphrase(PersonaDO persona) {
        Map<String, Object> definition = parseDefinition(persona);
        Object traits = definition.get("personalityTraits");
        if (!(traits instanceof Map<?, ?> traitMap)) {
            return "";
        }
        Object catchphrases = traitMap.get("catchphrases");
        if (!(catchphrases instanceof List<?> phrases) || phrases.isEmpty()) {
            return "";
        }
        return String.valueOf(phrases.get(ThreadLocalRandom.current().nextInt(phrases.size())));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDefinition(PersonaDO persona) {
        if (persona == null || persona.getDefinition() == null) {
            return Map.of();
        }
        try {
            return JsonMappers.shared().readValue(persona.getDefinition(), Map.class);
        } catch (Exception e) {
            log.warn("人格 definition 解析失败按空处理 persona={}", persona.getName(), e);
            return Map.of();
        }
    }
}
