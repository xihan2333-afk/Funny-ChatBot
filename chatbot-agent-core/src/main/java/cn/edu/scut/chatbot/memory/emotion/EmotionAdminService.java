package cn.edu.scut.chatbot.memory.emotion;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaDO;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.memory.persona.PersonaRuntime;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时情绪管理门面服务（A6）：列出各服务群当前情绪（含懒衰减有效强度与基线对照）、一键重置回基线。
 */
@Service
public class EmotionAdminService {

    private final GroupMapper groupMapper;
    private final EmotionService emotionService;
    private final PersonaResolver personaResolver;

    public EmotionAdminService(GroupMapper groupMapper,
                               EmotionService emotionService,
                               PersonaResolver personaResolver) {
        this.groupMapper = groupMapper;
        this.emotionService = emotionService;
        this.personaResolver = personaResolver;
    }

    public List<GroupEmotionView> listGroupEmotions() {
        List<GroupEmotionView> result = new ArrayList<>();
        for (GroupDO g : groupMapper.selectAllInService()) {
            GroupKey key = new GroupKey(g.getPlatform(), g.getBotId(), g.getGroupId());
            EmotionState state = emotionService.view(key);
            PersonaDO persona = personaResolver.resolve(g.getPlatform(), g.getBotId(), g.getGroupId());
            String baseLabel = persona != null ? PersonaRuntime.parse(persona).emotion().baseLabel() : "calm";
            int baseIntensity = 50;
            result.add(new GroupEmotionView(
                    g.getPlatform(), g.getBotId(), g.getGroupId(), g.getGroupName(),
                    state.label(), state.intensity(), state.intensity(),
                    baseLabel, baseIntensity, state.cause(),
                    state.updatedAt() != null ? state.updatedAt().toString() : null));
        }
        return result;
    }

    public void resetToBaseline(String platform, String botId, String groupId) {
        emotionService.resetToBaseline(new GroupKey(platform, botId, groupId));
    }

    public record GroupEmotionView(
            String platform, String botId, String groupId, String groupName,
            String label, int rawIntensity, int effectiveIntensity,
            String baselineLabel, int baselineIntensity, String cause, String updatedAt
    ) {
    }
}
