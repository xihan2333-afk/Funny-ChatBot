package cn.edu.scut.chatbot.memory.persona;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.GroupConfigDO;
import cn.edu.scut.chatbot.engine.intake.GroupConfigMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 人格解析器（自 prompt 包正位迁入——3.1.3 既定）：身份层所需字段子集 +
 * 注意力偏好直取（L1 输入）+ 表达变体加权抽取（权重归一，Run 启动时定——同 Run 内稳定）。
 * prompt 包的 PromptAssembler 是本类的消费方（依赖方向 prompt → memory）。
 */
@Component
public class PersonaResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonaResolver.class);

    private final PersonaMapper personaMapper;
    private final AgentSessionMapper sessionMapper;
    private final GroupConfigMapper groupConfigMapper;

    public PersonaResolver(PersonaMapper personaMapper, AgentSessionMapper sessionMapper) {
        this(personaMapper, sessionMapper, null);
    }

    @Autowired
    public PersonaResolver(PersonaMapper personaMapper,
                           AgentSessionMapper sessionMapper,
                           GroupConfigMapper groupConfigMapper) {
        this.personaMapper = personaMapper;
        this.sessionMapper = sessionMapper;
        this.groupConfigMapper = groupConfigMapper;
    }

    /**
     * 解析当前人格：会话绑定优先，未建会话时读取 group_config.persona_id 兜底，最后回落默认人格（种子 V4 预置）
     */
    public PersonaDO resolve(String platform, String botId, String groupId) {
        if (groupId != null) {
            AgentSessionDO session = sessionMapper.selectByGroup(platform, botId, groupId);
            if (session != null && session.getPersonaId() != null) {
                PersonaDO found = findPersonaById(session.getPersonaId());
                if (found != null) {
                    return found;
                }
            }
            if (groupConfigMapper != null) {
                GroupConfigDO cfg = groupConfigMapper.selectByGroup(platform, botId, groupId);
                if (cfg != null && cfg.getPersonaId() != null) {
                    PersonaDO found = findPersonaById(cfg.getPersonaId());
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return personaMapper.selectDefaultPersona();
    }

    private PersonaDO findPersonaById(long personaId) {
        for (PersonaDO persona : personaMapper.selectAllPersonas()) {
            if (personaId == persona.getId()) {
                return persona;
            }
        }
        return null;
    }

    /**
     * 人格切换（T039）：改 agent_session.persona_id 下个 Run 生效（快照纪律——
     * 进行中 Run 不受扰）；记忆画像群状态不随切换清空（换人格不丢记忆——本方法
     * 结构性不触碰记忆与画像表）；情绪重置为新人格基线。
     */
    public void switchPersona(cn.edu.scut.chatbot.bootstrap.GroupKey group, long newPersonaId) {
        AgentSessionDO session = sessionMapper.selectByGroup(
                group.platform(), group.botId(), group.groupId());
        if (session == null) {
            throw new IllegalArgumentException("会话不存在，先在群内说话建立会话："
                    + group.groupId());
        }
        PersonaDO target = null;
        for (PersonaDO persona : personaMapper.selectAllPersonas()) {
            if (newPersonaId == persona.getId()) {
                target = persona;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("目标人格不存在 personaId=" + newPersonaId);
        }
        sessionMapper.updatePersona(session.getId(), newPersonaId);
        // 情绪重置为新人格基线（label=基线标签、强度回基线档）——崩溃恢复语义同源
        String baseLabel = PersonaRuntime.parse(target).emotion().baseLabel();
        sessionMapper.updateEmotion(session.getId(),
                "{\"label\":\"" + baseLabel + "\",\"intensity\":50}");
        log.info("人格切换 group={} → personaId={}（{}）情绪回基线 {}",
                group.groupId(), newPersonaId, target.getName(), baseLabel);
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
