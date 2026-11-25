package cn.edu.scut.chatbot.engine.intake;

import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.gate.L0Gate;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import cn.edu.scut.chatbot.infrastructure.redis.InboundStreamConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 入流分发器（T043、契约 redis-streams-agent-core.md 第 1 节）：实现 InboundStreamConsumer
 * 的处理回调——消费线程只搬运，本组件在群串行泵内做「登记 → L0 判定 → 决策回填 / L1 升级」。
 *
 * <p>L0 accept 的触发器：直接产生 generation Effect（Phase 5 TurnCoordinator 就位后改为
 * acceptTurn 排队路径；本 Phase 先以 generation Effect 驱动，Turn 创建在 Effect 处理器内完成）。
 */
@Component
public class InboundDispatch implements InboundStreamConsumer.InboundHandler {

    private static final Logger log = LoggerFactory.getLogger(InboundDispatch.class);

    private final IntakeService intakeService;
    private final TriggerEventMapper triggerEventMapper;
    private final EffectsQueueMapper effectsQueueMapper;
    private final GroupMapper groupMapper;
    private final GroupConfigMapper groupConfigMapper;
    private final GroupStateHash groupStateHash;
    private final L0Gate l0Gate;
    private final cn.edu.scut.chatbot.engine.turn.TurnCoordinator turnCoordinator;
    private final SystemParamService systemParamService;
    private final Clock clock;
    private final cn.edu.scut.chatbot.memory.emotion.EmotionRuleEngine emotionRuleEngine;
    private final cn.edu.scut.chatbot.memory.social.GroupStateService groupStateService;
    private final cn.edu.scut.chatbot.memory.social.TopicLifecycleService topicLifecycleService;

    public InboundDispatch(IntakeService intakeService,
                           TriggerEventMapper triggerEventMapper,
                           EffectsQueueMapper effectsQueueMapper,
                           GroupMapper groupMapper,
                           GroupConfigMapper groupConfigMapper,
                           GroupStateHash groupStateHash,
                           L0Gate l0Gate,
                           cn.edu.scut.chatbot.engine.turn.TurnCoordinator turnCoordinator,
                           SystemParamService systemParamService,
                           Clock clock,
                           cn.edu.scut.chatbot.memory.emotion.EmotionRuleEngine emotionRuleEngine,
                           cn.edu.scut.chatbot.memory.social.GroupStateService groupStateService,
                           cn.edu.scut.chatbot.memory.social.TopicLifecycleService topicLifecycleService) {
        this.intakeService = intakeService;
        this.triggerEventMapper = triggerEventMapper;
        this.effectsQueueMapper = effectsQueueMapper;
        this.groupMapper = groupMapper;
        this.groupConfigMapper = groupConfigMapper;
        this.groupStateHash = groupStateHash;
        this.l0Gate = l0Gate;
        this.turnCoordinator = turnCoordinator;
        this.systemParamService = systemParamService;
        this.clock = clock;
        this.emotionRuleEngine = emotionRuleEngine;
        this.groupStateService = groupStateService;
        this.topicLifecycleService = topicLifecycleService;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        // 回执与 BotEvent 在 IntakeService 内分流；其余事件登记后过 L0
        if (PrimitiveTypeCodes.DELIVERY_REPORT.equals(envelope.type())
                || PrimitiveTypeCodes.BOT_EVENT.equals(envelope.type())) {
            intakeService.dispatch(envelope);
            return;
        }

        intakeService.registerTrigger(envelope);

        // 幂等场景：重复投递已被拒插，查不到本条新登记时直接结束（旧行已有决策）
        TriggerEventDO trigger = triggerEventMapper.selectByDedupKey(
                envelope.platform(), envelope.botId(), envelope.dedupKey());
        if (trigger == null || !TriggerEventDO.DECISION_PENDING.equals(trigger.getDecision())) {
            return;
        }

        // 刷新群活跃（调度器静默判定输入）
        if (envelope.groupId() != null) {
            groupStateHash.touchActive(envelope.platform(), envelope.botId(), envelope.groupId());
            // 情绪挂点（T046）：与 L0 上下文构建同点、同群串行执行器内顺带执行——
            // 识别互动事件走规则通道（一次事件双落点）；情绪不进 L0 判定（L0 纯代码）
            emotionRuleEngine.onGroupMessage(envelope);
            // 群状态与话题挂点（T055）：同群串行执行器内顺带执行——六状态项事件驱动更新
            // 与话题再热判定（keywords 轻量匹配），天然保序不并发
            cn.edu.scut.chatbot.bootstrap.GroupKey groupKey = new cn.edu.scut.chatbot.bootstrap.GroupKey(
                    envelope.platform(), envelope.botId(), envelope.groupId());
            String messageText = messageTextOf(envelope);
            groupStateService.onGroupMessage(groupKey, senderUserIdOf(envelope), messageText);
            topicLifecycleService.onGroupMessage(groupKey, messageText);
        }

        L0Gate.L0Decision decision = l0Gate.judge(buildL0Context(envelope));
        if (decision.escalateToL1()) {
            // 升级：入 assessment Effect（同群同 kind 合并——积压一次评估）
            enqueueEffect(envelope, EffectsQueueDO.KIND_ASSESSMENT, "{}");
            return;
        }
        triggerEventMapper.updateDecision(trigger.getId(), decision.decision(), decision.decisionLayer(),
                decision.reason(), null, LocalDateTime.now(clock));
        if ("accept".equals(decision.decision())) {
            // L0 强制接受：创建 Turn（含 generation Effect 入队与同批合并）
            try {
                turnCoordinator.acceptTurn(envelope.platform(), envelope.botId(),
                        envelope.groupId(), trigger.getId());
            } catch (CommandRejectedException rejected) {
                // 排队超上限：合法拒绝路径——决策已留档，Turn 不创建
                log.warn("acceptTurn 被拒绝（排队超上限） reason={}", rejected.getReason());
            }
        }
    }

    /**
     * 消息纯文本（TextPart 拼接——群状态与话题挂点的输入；非群消息返回空串）
     */
    private String messageTextOf(EventEnvelope envelope) {
        if (!(envelope.payload() instanceof GroupMessagePayload groupMessage)
                || groupMessage.parts() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (var part : groupMessage.parts()) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }

    /**
     * 发言人（群消息 sender 优先、回退 envelope.userId）
     */
    private String senderUserIdOf(EventEnvelope envelope) {
        if (envelope.payload() instanceof GroupMessagePayload groupMessage
                && groupMessage.sender() != null) {
            return groupMessage.sender().userId();
        }
        return envelope.userId();
    }

    private L0Gate.L0Context buildL0Context(EventEnvelope envelope) {
        GroupDO group = envelope.groupId() != null
                ? groupMapper.selectByGroup(envelope.platform(), envelope.botId(), envelope.groupId())
                : null;
        GroupConfigDO config = envelope.groupId() != null
                ? groupConfigMapper.selectByGroup(envelope.platform(), envelope.botId(), envelope.groupId())
                : null;

        long cooldownMillis = systemParamService.getLong("gate.cooldown_millis", 8000);
        int rateLimit = systemParamService.getInt("gate.rate_limit_per_minute", 6);

        boolean atBot = false;
        boolean slashCommand = false;
        String senderUserId = envelope.userId();
        if (envelope.payload() instanceof GroupMessagePayload groupMessage) {
            if (groupMessage.sender() != null) {
                senderUserId = groupMessage.sender().userId();
            }
            for (var part : groupMessage.parts()) {
                if (part instanceof AtPart atPart && envelope.botId() != null
                        && envelope.botId().equals(atPart.targetUserId())) {
                    atBot = true;
                }
                if (part instanceof TextPart textPart && textPart.text() != null
                        && textPart.text().startsWith("/")) {
                    slashCommand = true;
                }
            }
        }

        return new L0Gate.L0Context(
                envelope.type(),
                senderUserId,
                atBot,
                slashCommand,
                isBotSelf(envelope),
                group != null && GroupDO.LIFECYCLE_PAUSED.equals(group.getLifecycle()),
                blacklistOf(config),
                envelope.groupId() != null
                        ? groupStateHash.cooldownRemainingMillis(
                        envelope.platform(), envelope.botId(), envelope.groupId(), cooldownMillis)
                        : 0,
                envelope.groupId() != null
                        ? groupStateHash.sendCountInWindow(envelope.platform(), envelope.botId(), envelope.groupId())
                        : 0,
                rateLimit);
    }

    /**
     * Bot 自身消息判定：群消息发送者即 botId（防自我激励——即使 @ 自己也忽略）
     */
    private boolean isBotSelf(EventEnvelope envelope) {
        if (envelope.payload() instanceof GroupMessagePayload groupMessage
                && groupMessage.sender() != null) {
            return envelope.botId() != null
                    && envelope.botId().equals(groupMessage.sender().userId());
        }
        return false;
    }

    private List<String> blacklistOf(GroupConfigDO config) {
        if (config == null || config.getUserBlacklist() == null) {
            return List.of();
        }
        try {
            List<String> parsed = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared()
                    .readValue(config.getUserBlacklist(),
                            cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().getTypeFactory()
                                    .constructCollectionType(List.class, String.class));
            return parsed != null ? parsed : List.of();
        } catch (Exception e) {
            log.warn("黑名单 JSON 解析失败，按空黑名单处理", e);
            return List.of();
        }
    }

    private void enqueueEffect(EventEnvelope envelope, String kind, String payload) {
        EffectsQueueDO effect = new EffectsQueueDO();
        effect.setPlatform(envelope.platform());
        effect.setBotId(envelope.botId());
        effect.setGroupId(envelope.groupId() != null ? envelope.groupId() : "__global__");
        effect.setKind(kind);
        effect.setPayload(payload);
        effect.setNextRunAt(LocalDateTime.now(clock));
        effectsQueueMapper.enqueueMerge(effect);
    }
}
