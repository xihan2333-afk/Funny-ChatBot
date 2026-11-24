package cn.edu.scut.chatbot.engine.session;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.context.window.InputTruncator;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.in.*;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.DeliveryReportEntry;
import cn.edu.scut.chatbot.loop.message.EventEntry;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转录写入侧生产者：入方向的三类事实——群友消息、群事件、投递回执——写入会话转录。
 *
 * <p>存储全量纪律（设计方案 2.2「存了再筛」）：发生过的一切都有条目可查，
 * 喂给模型时再由投影按可见性策略筛选；条目内容入库前经源头截断
 * （群友消息 2000 字符宽松截断）。调用方事务内直接参与（mapper 调用无独立事务边界）。
 */
@Component
public class TranscriptIntakeWriter {

    private static final Logger log = LoggerFactory.getLogger(TranscriptIntakeWriter.class);

    /**
     * UserMessage 默认投影权重（与 TranscriptCodec 反序列化默认值一致）
     */
    private static final int WEIGHT_USER = 50;

    private final SessionManager sessionManager;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final cn.edu.scut.chatbot.context.link.LinkPrefetcher linkPrefetcher;

    public TranscriptIntakeWriter(SessionManager sessionManager,
                                  IdGenerator idGenerator,
                                  Clock clock,
                                  cn.edu.scut.chatbot.context.link.LinkPrefetcher linkPrefetcher) {
        this.sessionManager = sessionManager;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.linkPrefetcher = linkPrefetcher;
    }

    /**
     * 入方向事实写入（触发器登记成功后同事务调用；重复投递不重复写——幂等由调用方保证）。
     * 群消息 → UserMessage；成员/互动/群维度事件 → EventEntry。回执与 BotEvent 不走此方法
     * （分别由 OutboxWriter 结算与生命周期联动写入）。
     * Bot 自身发言的回显跳过：接入层把 message_sent 翻成群消息回流，L0 一律忽略，
     * 转录侧同样不写——否则同一句发言以 UserMessage + AssistantMessage 双份进历史。
     */
    public void writeInbound(EventEnvelope envelope) {
        if (envelope.payload() instanceof GroupMessagePayload groupMessage
                && groupMessage.sender() != null
                && envelope.botId() != null
                && envelope.botId().equals(groupMessage.sender().userId())) {
            return;
        }
        AgentMessage entry = toEntry(envelope);
        if (entry == null) {
            return;
        }
        AgentSessionDO session = sessionManager.getOrCreateSession(
                envelope.platform(), envelope.botId(), envelope.groupId());
        sessionManager.appendTranscript(session, List.of(entry), null);
        // 链接预取管线（异步——卡片是增强信息，预取慢不挡登记事务；
        // 卡片追加转录尾部，快照纪律下只影响下个 Run）
        if (entry instanceof UserMessage userMessage) {
            String text = userText(userMessage);
            if (text != null && text.contains("http")) {
                String sharer = userMessage.senderUserId();
                Thread.ofVirtual().name("link-prefetch").start(() ->
                        linkPrefetcher.prefetchAndAppend(session, text, sharer));
            }
        }
    }

    private String userText(UserMessage message) {
        StringBuilder builder = new StringBuilder();
        for (MessagePart part : message.parts()) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                builder.append(textPart.text()).append(' ');
            }
        }
        return builder.isEmpty() ? null : builder.toString().trim();
    }

    /**
     * 投递回执写入（OutboxWriter 结算成功后同事务调用）：成功压缩为状态行、失败携带原因，
     * 进入历史后下一次推理才可见（回执不注入进行中的生成）。
     */
    public void writeDeliveryReport(DeliveryReportPayload report, String platform,
                                    String botId, String groupId) {
        if (platform == null || botId == null || groupId == null || report == null) {
            return;
        }
        Instant occurredAt = report.attemptedAt() != null
                ? Instant.ofEpochMilli(report.attemptedAt()) : clock.instant();
        DeliveryReportEntry entry = new DeliveryReportEntry(
                "report-" + report.outboxId(),
                occurredAt, WEIGHT_USER, report.outboxId(), report.outboxId(),
                report.status(), report.failureKind(), report.failureDetail());
        AgentSessionDO session = sessionManager.getOrCreateSession(platform, botId, groupId);
        sessionManager.appendTranscript(session, List.of(entry), null);
    }

    /**
     * BotEvent 写入（生命周期联动的非销毁分支调用）：Bot 自身事件对人格可见
     * （被禁言、掉线等是"群里发生了与自己有关的事"）；销毁联动分支不写（群已卸载）。
     */
    public void writeBotEvent(EventEnvelope envelope, BotEventPayload payload) {
        if (envelope.groupId() == null) {
            return;   // 全局事件（friend_added 等）无群会话可挂
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("subType", payload.subType());
        putIfNotNull(attributes, "operatorUserId", payload.operatorUserId());
        if (payload.durationSeconds() != null) {
            attributes.put("durationSeconds", String.valueOf(payload.durationSeconds()));
        }
        EventEntry entry = new EventEntry(
                envelope.eventId() != null ? envelope.eventId()
                        : Long.toUnsignedString(idGenerator.nextId()),
                occurredAt(envelope), EventEntry.WEIGHT_INTERACTION,
                sourceRef(envelope), EventEntry.CATEGORY_BOT_EVENT, attributes,
                botEventLine(envelope, payload));
        AgentSessionDO session = sessionManager.getOrCreateSession(
                envelope.platform(), envelope.botId(), envelope.groupId());
        sessionManager.appendTranscript(session, List.of(entry), null);
    }

    private AgentMessage toEntry(EventEnvelope envelope) {
        if (envelope.payload() instanceof GroupMessagePayload groupMessage) {
            return toUserMessage(envelope, groupMessage);
        }
        if (envelope.payload() instanceof MemberEventPayload memberEvent) {
            return toMemberEventEntry(envelope, memberEvent);
        }
        if (envelope.payload() instanceof InteractionPayload interaction) {
            return toInteractionEventEntry(envelope, interaction);
        }
        if (envelope.payload() instanceof GroupEventPayload groupEvent) {
            return toGroupEventEntry(envelope, groupEvent);
        }
        return null;   // raw_event 等兜底原语：留档在接入层，不产生转录条目
    }

    private UserMessage toUserMessage(EventEnvelope envelope, GroupMessagePayload payload) {
        List<MessagePart> truncated = new ArrayList<>(payload.parts().size());
        for (MessagePart part : payload.parts()) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                truncated.add(new TextPart(TextPart.TYPE_TEXT,
                        InputTruncator.truncateGroupMessage(textPart.text())));
            } else {
                truncated.add(part);
            }
        }
        return new UserMessage(
                payload.messageId() != null ? payload.messageId()
                        : Long.toUnsignedString(idGenerator.nextId()),
                occurredAt(envelope), WEIGHT_USER, sourceRef(envelope),
                payload.sender() != null ? payload.sender().userId() : null,
                truncated);
    }

    private EventEntry toMemberEventEntry(EventEnvelope envelope, MemberEventPayload payload) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("subType", payload.subType());
        putIfNotNull(attributes, "operatorUserId", payload.operatorUserId());
        if (payload.durationSeconds() != null) {
            attributes.put("durationSeconds", String.valueOf(payload.durationSeconds()));
        }
        return new EventEntry(
                envelope.eventId() != null ? envelope.eventId()
                        : Long.toUnsignedString(idGenerator.nextId()),
                occurredAt(envelope), EventEntry.WEIGHT_MEMBER_CHANGE,
                sourceRef(envelope), EventEntry.CATEGORY_MEMBER_CHANGE, attributes,
                memberEventLine(envelope, payload));
    }

    private EventEntry toInteractionEventEntry(EventEnvelope envelope, InteractionPayload payload) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("subType", payload.subType());
        putIfNotNull(attributes, "targetUserId", payload.targetUserId());
        putIfNotNull(attributes, "operatorUserId", payload.operatorUserId());
        return new EventEntry(
                envelope.eventId() != null ? envelope.eventId()
                        : Long.toUnsignedString(idGenerator.nextId()),
                occurredAt(envelope), EventEntry.WEIGHT_INTERACTION,
                sourceRef(envelope), EventEntry.CATEGORY_INTERACTION, attributes,
                interactionLine(envelope, payload));
    }

    private EventEntry toGroupEventEntry(EventEnvelope envelope, GroupEventPayload payload) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("subType", payload.subType());
        return new EventEntry(
                envelope.eventId() != null ? envelope.eventId()
                        : Long.toUnsignedString(idGenerator.nextId()),
                occurredAt(envelope), EventEntry.WEIGHT_MEMBER_CHANGE,
                sourceRef(envelope), EventEntry.CATEGORY_GROUP_CHANGE, attributes,
                "[事件] 群信息变更（" + payload.subType() + "）");
    }

    private Instant occurredAt(EventEnvelope envelope) {
        return envelope.occurredAt() != null
                ? Instant.ofEpochMilli(envelope.occurredAt()) : clock.instant();
    }

    private String sourceRef(EventEnvelope envelope) {
        if (envelope.rawRef() != null && envelope.rawRef().rawEventId() != null) {
            return envelope.rawRef().rawEventId();
        }
        return envelope.eventId();
    }

    private void putIfNotNull(Map<String, String> attributes, String key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    /**
     * 成员变动一行描述（投影默认形态的素材；无人名解析，用户以平台号指代）
     */
    private String memberEventLine(EventEnvelope envelope, MemberEventPayload payload) {
        String who = envelope.userId() != null ? envelope.userId() : "某成员";
        String operator = payload.operatorUserId() != null
                ? "（由 " + payload.operatorUserId() + " 操作）" : "";
        return switch (payload.subType()) {
            case MemberEventPayload.SUB_MEMBER_JOIN -> "[事件] 用户 " + who + " 加入了群";
            case MemberEventPayload.SUB_MEMBER_LEAVE -> "[事件] 用户 " + who + " 退出了群";
            case MemberEventPayload.SUB_MEMBER_KICKED -> "[事件] 用户 " + who + " 被移出了群" + operator;
            case MemberEventPayload.SUB_MUTED -> "[事件] 用户 " + who + " 被禁言 "
                    + (payload.durationSeconds() != null ? payload.durationSeconds() + " 秒" : "") + operator;
            case MemberEventPayload.SUB_UNMUTED -> "[事件] 用户 " + who + " 被解除禁言" + operator;
            case MemberEventPayload.SUB_ADMIN_GRANTED -> "[事件] 用户 " + who + " 被设为管理员" + operator;
            case MemberEventPayload.SUB_ADMIN_REVOKED -> "[事件] 用户 " + who + " 被撤销管理员" + operator;
            case MemberEventPayload.SUB_CARD_CHANGED -> "[事件] 用户 " + who + " 修改了群名片";
            default -> "[事件] 成员变动（" + payload.subType() + "）：" + who;
        };
    }

    /**
     * 互动事件一行描述
     */
    private String interactionLine(EventEnvelope envelope, InteractionPayload payload) {
        String who = envelope.userId() != null ? envelope.userId() : "某用户";
        String target = payload.targetUserId() != null
                ? " 用户 " + payload.targetUserId() : "";
        return switch (payload.subType()) {
            case InteractionPayload.SUB_POKE -> "[事件] 用户 " + who + " 戳了戳" + target;
            case InteractionPayload.SUB_MESSAGE_RECALL -> "[事件]" + target + " 的消息被撤回";
            case InteractionPayload.SUB_FILE_UPLOAD -> "[事件] 用户 " + who + " 上传了群文件";
            case InteractionPayload.SUB_RED_PACKET_LUCKY -> "[事件] 用户 " + who + " 成为红包运气王";
            case InteractionPayload.SUB_HONOR -> "[事件] 用户 " + who + " 获得群荣誉";
            case InteractionPayload.SUB_REACTION -> "[事件] 用户 " + who + " 对消息做了表情回应";
            case InteractionPayload.SUB_ESSENCE -> "[事件] 用户 " + who + " 的消息被设为精华";
            default -> "[事件] 互动（" + payload.subType() + "）：用户 " + who + target;
        };
    }

    /**
     * BotEvent 一行描述
     */
    private String botEventLine(EventEnvelope envelope, BotEventPayload payload) {
        String operator = payload.operatorUserId() != null
                ? "（由 " + payload.operatorUserId() + " 操作）" : "";
        return switch (payload.subType()) {
            case BotEventPayload.SUB_BOT_MUTED -> "[事件] 你被禁言了 "
                    + (payload.durationSeconds() != null ? payload.durationSeconds() + " 秒" : "") + operator;
            case BotEventPayload.SUB_BOT_UNMUTED -> "[事件] 你被解除禁言" + operator;
            case BotEventPayload.SUB_BOT_OFFLINE -> "[事件] 你的会话掉线过，期间的消息可能不完整";
            default -> "[事件] Bot 事件（" + payload.subType() + "）";
        };
    }
}
