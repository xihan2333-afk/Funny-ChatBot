package cn.edu.scut.chatbot.tool.builtin;

import cn.edu.scut.chatbot.exception.ToolExecutionException;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.contract.envelope.PrimitiveTypeCodes;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.engine.outbox.OutboxWriter;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import cn.edu.scut.chatbot.loop.message.TextContent;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * sendMessage 工具：对群发言的唯一出口（宪法第三条——一切对外发言只有这一个出口，
 * 绕过该出口直发平台 API 的代码路径不得存在）。执行即投递：Outbox 先落库取得
 * outboxId 再入 dispatch Effect（附件回带 outboxId 供发言三结局判定）。
 */
@Component
public class SendMessageTool implements AgentTool {

    private static final String SCHEMA = """
            {"type":"object","properties":{
              "text":{"type":"string","description":"发言文本，口语化、单条不超过 100 字"},
              "replyToMessageId":{"type":"string","description":"要引用回复的平台消息 ID，可省"}
            },"required":["text"]}""";

    private final OutboxWriter outboxWriter;

    public SendMessageTool(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "sendMessage",
                "向当前群发送一条消息。这是你对群里说话的唯一方式——不调用本工具等于你没说话。"
                        + "一次发言 1~3 条、口语化；不确定该不该说就不调用。",
                SCHEMA,
                ToolDescriptor.ToolSpeed.FAST,
                ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT,
                ToolDescriptor.ToolSource.BUILTIN,
                false);
    }

    @Override
    public AgentToolResult execute(ToolInvocation invocation) throws ToolExecutionException {
        try {
            JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
            String text = arguments.path("text").asText(null);
            if (text == null || text.isBlank()) {
                return AgentToolResult.error("text 参数不能为空（要说什么就写什么）");
            }
            String replyTo = arguments.path("replyToMessageId").asText(null);

            List<MessagePart> parts = new ArrayList<>();
            parts.add(new TextPart(TextPart.TYPE_TEXT, text));
            String payloadJson = JsonMappers.valueToStringOrNull(
                    new cn.edu.scut.chatbot.contract.out.SendGroupMessagePayload(parts, replyTo));

            long outboxId = outboxWriter.enqueueOutbound(
                    invocation.platform(), invocation.botId(), invocation.groupId(),
                    PrimitiveTypeCodes.SEND_GROUP_MESSAGE, payloadJson,
                    invocation.turnId(), invocation.taskId());
            return new AgentToolResult(
                    List.of(new TextContent("已发送（outboxId=" + outboxId + "）")),
                    false, false, Map.of("outboxId", String.valueOf(outboxId)));
        } catch (Exception e) {
            throw new ToolExecutionException("tool.execution_failed", "sendMessage 执行失败：" + e.getMessage(), e);
        }
    }
}
