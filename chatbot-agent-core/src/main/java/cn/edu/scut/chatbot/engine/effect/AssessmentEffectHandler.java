package cn.edu.scut.chatbot.engine.effect;

import cn.edu.scut.chatbot.engine.gate.L1BatchAssessor;
import org.springframework.stereotype.Component;

/**
 * assessment 处理器：驱动该群的 L1 批量评估（同群 pending 触发器合并一次调用）。
 */
@Component
public class AssessmentEffectHandler implements EffectHandler {

    private final L1BatchAssessor assessor;

    public AssessmentEffectHandler(L1BatchAssessor assessor) {
        this.assessor = assessor;
    }

    @Override
    public String kind() {
        return EffectsQueueDO.KIND_ASSESSMENT;
    }

    @Override
    public void handle(EffectsQueueDO effect) {
        assessor.assessGroup(effect.getPlatform(), effect.getBotId(), effect.getGroupId());
    }
}
