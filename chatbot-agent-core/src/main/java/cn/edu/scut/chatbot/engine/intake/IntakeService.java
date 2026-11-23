package cn.edu.scut.chatbot.engine.intake;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.in.BotEventPayload;
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 入口服务（spec US2、research R2）：触发器登记（幂等）、群自动发现（与登记同事务）、
 * 未服务群拦截、BotEvent 生命周期联动。
 *
 * <p>未服务群（in_service=0 或 DISCOVERED）：消息不登记触发器不留档——运营已做出明确
 * 决策（不服务），系统无须再评估；BotEvent 例外（被踢出群触发销毁联动）。
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final TriggerEventMapper triggerEventMapper;
    private final GroupMapper groupMapper;
    private final GroupConfigMapper groupConfigMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 回执结算分流口（US3 OutboxWriter 就位后挂接，先留接口——Phase 4 期间回执到达即记日志）
     */
    public interface DeliverySettlementSink {

        void settle(EventEnvelope deliveryReport);
    }

    private final DeliverySettlementSink deliverySink;

    /**
     * BotEvent 生命周期联动口（US8 GroupLifecycleManager 就位后挂接）
     */
    public interface LifecycleHook {

        void onBotEvent(EventEnvelope envelope, BotEventPayload payload);
    }

    private final LifecycleHook lifecycleHook;

    public IntakeService(TriggerEventMapper triggerEventMapper,
                         GroupMapper groupMapper,
                         GroupConfigMapper groupConfigMapper,
                         IdGenerator idGenerator,
                         Clock clock,
                         DeliverySettlementSink deliverySink,
                         LifecycleHook lifecycleHook) {
        this.triggerEventMapper = triggerEventMapper;
        this.groupMapper = groupMapper;
        this.groupConfigMapper = groupConfigMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.deliverySink = deliverySink;
        this.lifecycleHook = lifecycleHook;
    }

    /**
     * 入流分发主入口（InboundDispatch 调用；已在群串行泵内）。
     * 回执分流结算；BotEvent 走生命周期联动；普通事件走登记 + 群发现。
     */
    public void dispatch(EventEnvelope envelope) {
        if (PrimitiveTypeCodes.DELIVERY_REPORT.equals(envelope.type())) {
            deliverySink.settle(envelope);
            return;
        }
        if (PrimitiveTypeCodes.BOT_EVENT.equals(envelope.type())
                && envelope.payload() instanceof BotEventPayload botEvent) {
            lifecycleHook.onBotEvent(envelope, botEvent);
            return;
        }
        registerTrigger(envelope);
    }

    /**
     * 登记触发器（幂等）：uk_dedup 拒插即重复投递，静默返回。
     * 群自动发现：信封带 groupId 而群表无此群 → 同事务 UPSERT（DISCOVERED + 默认配置行）。
     */
    @Transactional
    public void registerTrigger(EventEnvelope envelope) {
        String platform = envelope.platform();
        String botId = envelope.botId();
        String groupId = envelope.groupId();

        // 群自动发现与配置行补建（与登记同事务——崩溃不产生"有触发器无群行"的孤儿）
        groupMapper.upsertDiscovered(platform, botId, groupId, null);
        groupConfigMapper.upsertDefault(platform, botId, groupId);

        GroupDO group = groupMapper.selectByGroup(platform, botId, groupId);
        boolean inService = group != null
                && group.getInService() != null && group.getInService() == 1
                && !GroupDO.LIFECYCLE_DISCOVERED.equals(group.getLifecycle());
        if (!inService) {
            // 未服务群：不登记触发器不留档（research R2——运营决策不是系统判断）
            log.debug("群未在服务名单，事件直接忽略 platform={} botId={} groupId={} dedupKey={}",
                    platform, botId, groupId, envelope.dedupKey());
            return;
        }

        TriggerEventDO trigger = new TriggerEventDO();
        trigger.setId(idGenerator.nextId());
        trigger.setPlatform(platform);
        trigger.setBotId(botId);
        trigger.setGroupId(groupId);
        trigger.setTriggerKind(TriggerEventDO.KIND_EXTERNAL);
        trigger.setEventType(envelope.type());
        trigger.setDedupKey(envelope.dedupKey());
        trigger.setDigest(digestOf(envelope));
        trigger.setSourceRef(envelope.rawRef() != null
                ? envelope.rawRef().rawEventId() : null);
        trigger.setDecision(TriggerEventDO.DECISION_PENDING);
        trigger.setOccurredAt(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(envelope.occurredAt() != null
                        ? envelope.occurredAt() : clock.millis()), clock.getZone()));
        try {
            triggerEventMapper.insertTrigger(trigger);
        } catch (DuplicateKeyException duplicate) {
            // 幂等：uk_dedup 拒插即重复投递（至少一次语义的正常形态），静默返回
            log.debug("重复投递幂等拦截 dedupKey={}", envelope.dedupKey());
        }
    }

    /**
     * 触发内容摘要（L0/L1 的评估输入）：群消息取文本前 200 字符，其他事件取类型摘要
     */
    private String digestOf(EventEnvelope envelope) {
        if (envelope.payload() instanceof GroupMessagePayload groupMessage) {
            StringBuilder builder = new StringBuilder();
            for (var part : groupMessage.parts()) {
                if (part instanceof TextPart textPart && textPart.text() != null) {
                    builder.append(textPart.text());
                } else if (part instanceof AtPart atPart) {
                    builder.append("@").append(atPart.targetUserId());
                }
            }
            String text = builder.toString();
            return text.length() > 200 ? text.substring(0, 200) : text;
        }
        return envelope.type() + (envelope.userId() != null ? " from " + envelope.userId() : "");
    }

    /**
     * 回执载荷（InboundDispatch 的回执分流用）
     */
    public static DeliveryReportPayload asDeliveryReport(EventEnvelope envelope) {
        return envelope.payload() instanceof DeliveryReportPayload payload ? payload : null;
    }
}
