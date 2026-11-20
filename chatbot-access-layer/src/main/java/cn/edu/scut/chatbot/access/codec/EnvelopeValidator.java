package cn.edu.scut.chatbot.access.codec;

import org.springframework.stereotype.Component;

import cn.edu.scut.chatbot.access.infrastructure.NonRetryableException;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.error.ErrorCodes;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.TextPart;

/**
 * 信封非空清单校验（data-model.md 第 2 节「验证规则」）。
 *
 * <p>两套清单：入信封（接入层组装后、XADD 之前自检）与出信封（Sender 消费出流时先校验，
 * 缺失 outboxId / traceContext 即毒消息，防 Agent 侧 bug 静默扩散）。
 * 违反即 Codec / 对端 bug，抛 NonRetryableException——这是接入层内部结构断言，不该发生。
 */
@Component
public class EnvelopeValidator {

    /**
     * 入信封校验：eventId、platform、botId、type、payload、receivedAt、dedupKey 非空；群维度事件 groupId 必填
     */
    public void validateInbound(EventEnvelope envelope) {
        require(envelope.eventId() != null && !envelope.eventId().isBlank(), "入信封 eventId 非空");
        require(envelope.outboxId() == null, "入信封 outboxId 必须为 null（方向约束）");
        require(envelope.platform() != null && !envelope.platform().isBlank(), "入信封 platform 非空");
        require(envelope.botId() != null && !envelope.botId().isBlank(), "入信封 botId 非空");
        require(envelope.type() != null && !envelope.type().isBlank(), "入信封 type 非空");
        require(envelope.payload() != null, "入信封 payload 非空");
        require(envelope.receivedAt() != null, "入信封 receivedAt 非空");
        require(envelope.dedupKey() != null && !envelope.dedupKey().isBlank(),
                "入信封 dedupKey 非空（notice/request 类兜底哈希保证恒有值）");
        require(envelope.dedupKey() == null || envelope.dedupKey().length() <= 128,
                "入信封 dedupKey 不得超过 128 字符");
        boolean groupScoped = envelope.type().equals(PrimitiveTypeCodes.GROUP_MESSAGE)
                || envelope.type().equals(PrimitiveTypeCodes.MEMBER_EVENT)
                || envelope.type().equals(PrimitiveTypeCodes.GROUP_EVENT);
        if (groupScoped) {
            require(envelope.groupId() != null && !envelope.groupId().isBlank(),
                    "群维度事件（" + envelope.type() + "）信封 groupId 必填");
        }
        if (envelope.type().equals(PrimitiveTypeCodes.GROUP_MESSAGE)) {
            validateGroupMessagePayload(envelope);
        }
    }

    /**
     * 群消息载荷级校验（data-model 第 3 节「验证规则」）：parts 非空、文本段 text 非空、@ 段目标非空
     */
    private void validateGroupMessagePayload(EventEnvelope envelope) {
        GroupMessagePayload payload = (GroupMessagePayload) envelope.payload();
        require(payload.parts() != null && !payload.parts().isEmpty(), "群消息 parts 必须是非空列表");
        require(payload.messageId() != null && !payload.messageId().isBlank(),
                "群消息 messageId 非空（它是 dedupKey 来源）");
        for (MessagePart part : payload.parts()) {
            if (part instanceof TextPart text) {
                require(text.text() != null && !text.text().isEmpty(), "文本段 text 非空");
            } else if (part instanceof AtPart at) {
                require(at.targetUserId() != null && !at.targetUserId().isBlank(),
                        "@段 targetUserId 非空");
            }
        }
    }

    /**
     * 出信封校验：outboxId、platform、botId、type、payload、traceContext 非空；SendGroupMessage 必带 groupId
     */
    public void validateOutbound(EventEnvelope envelope) {
        require(envelope.outboxId() != null && !envelope.outboxId().isBlank(),
                "出信封 outboxId 非空（Agent 侧先落库发放）");
        require(envelope.eventId() == null, "出信封 eventId 必须为 null（方向约束）");
        require(envelope.platform() != null && !envelope.platform().isBlank(), "出信封 platform 非空");
        require(envelope.botId() != null && !envelope.botId().isBlank(), "出信封 botId 非空");
        require(envelope.type() != null && !envelope.type().isBlank(), "出信封 type 非空");
        require(envelope.payload() != null, "出信封 payload 非空");
        require(envelope.traceContext() != null && !envelope.traceContext().isBlank(),
                "出信封 traceContext 非空（缺它即毒消息，回执 failed(internal)）");
        if (envelope.type().equals(PrimitiveTypeCodes.SEND_GROUP_MESSAGE)) {
            require(envelope.groupId() != null && !envelope.groupId().isBlank(),
                    "SendGroupMessage 信封 groupId 必填");
        }
    }

    private void require(boolean condition, String violationDescription) {
        if (!condition) {
            throw new NonRetryableException(ErrorCodes.ADAPTER_CODEC_INVALID_ENVELOPE,
                    "信封违反非空清单：" + violationDescription);
        }
    }
}
