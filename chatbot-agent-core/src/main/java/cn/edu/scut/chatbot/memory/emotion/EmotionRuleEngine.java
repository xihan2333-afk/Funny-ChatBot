package cn.edu.scut.chatbot.memory.emotion;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.message.AtPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 情绪规则引擎（T043）：互动事件的结构化识别 + 规则通道应用——纯代码零模型、
 * 事件驱动即时生效。识别门槛：事件必须是平台结构化事件代码可判定
 * （语义事件没有资格走规则通道——归 updateEmotion 自报）。
 *
 * <p>v1 识别面：群消息 @ bot（atMentioned）。poke / 表情回应 / 长时间无人回应 /
 * 被禁言 / 任务被拒的识别入口依赖接入层事件面扩展（规则表与 delta 消费端已就绪，
 * 事件源就位即接——偏离记录）。
 */
@Component
public class EmotionRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(EmotionRuleEngine.class);

    public static final String EVENT_AT_MENTIONED = "atMentioned";

    private final EmotionService emotionService;

    public EmotionRuleEngine(EmotionService emotionService) {
        this.emotionService = emotionService;
    }

    /**
     * 群消息事件驱动（同群串行执行器内顺带调用——天然保序不并发）。
     * 一次事件双落点在 EmotionService.applyRuleDelta 内完成（情绪 + 主体好感）。
     */
    public void onGroupMessage(EventEnvelope envelope) {
        if (envelope.groupId() == null || envelope.botId() == null
                || !(envelope.payload() instanceof GroupMessagePayload message)) {
            return;
        }
        String sender = message.sender() == null
                ? envelope.userId() : message.sender().userId();
        if (sender == null || sender.isBlank()) {
            return;
        }
        boolean atBot = false;
        for (var part : message.parts()) {
            if (part instanceof AtPart atPart && envelope.botId().equals(atPart.targetUserId())) {
                atBot = true;
                break;
            }
        }
        if (!atBot) {
            return;
        }
        try {
            emotionService.applyRuleDelta(new GroupKey(envelope.platform(), envelope.botId(),
                    envelope.groupId()), EVENT_AT_MENTIONED, sender);
        } catch (Exception e) {
            // 情绪是增强通道——失败不影响消息主流程
            log.warn("情绪规则应用失败（不影响消息处理） group={}：{}", envelope.groupId(), e.getMessage());
        }
    }
}
