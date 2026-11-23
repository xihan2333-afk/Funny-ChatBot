package cn.edu.scut.chatbot.loop.message;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 基准条目：工具执行结果（data-model 第 2 节）。isError 是业务正常分支（给模型看）；
 * 结构化附件承载跨组件关联（如 sendMessage 回带 outboxId）。
 */
public record ToolResultMessage(
        String messageId,
        Instant occurredAt,
        int weight,
        String sourceRef,
        String callId,
        String toolName,
        List<ContentPart> content,
        boolean isError,
        boolean terminate,
        Map<String, Object> attachments
) implements AgentMessage {

    @Override
    public AgentMessageKind kind() {
        return AgentMessageKind.TOOL_RESULT;
    }
}
