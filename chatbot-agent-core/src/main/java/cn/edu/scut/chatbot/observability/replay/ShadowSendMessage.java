package cn.edu.scut.chatbot.observability.replay;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.exception.ToolExecutionException;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 影子 sendMessage（eval-and-flywheel 契约第 4 节铁律一）：dry-run 重放中对群发言
 * 的唯一替身——执行结果只写入重放报告，<b>Outbox 零写入、平台零投递</b>（拦截发生在
 * 出口层而非模型自觉：模型侧看它就是 sendMessage，行为完全一致）。
 *
 * <p>非 @Component：由 {@link ReplayToolAssembler} 在重放装配时按次构造并持有
 * 发言收集器，Run 结束后取走清单进 ReplayReport。
 */
public class ShadowSendMessage implements AgentTool {

    private final ToolDescriptor delegateDescriptor;

    private final List<ShadowSpeech> speeches = new ArrayList<>();

    public ShadowSendMessage(AgentTool realSendMessage) {
        this.delegateDescriptor = realSendMessage.descriptor();
    }

    /**
     * 单条影子发言（重放报告的明细行）
     */
    public record ShadowSpeech(String text, String replyToMessageId) {
    }

    /**
     * Run 结束后取走本次收集的全部发言（不清空——报告归档后整个实例随 Run 丢弃）
     */
    public List<ShadowSpeech> speeches() {
        return List.copyOf(speeches);
    }

    @Override
    public ToolDescriptor descriptor() {
        return delegateDescriptor;
    }

    @Override
    public AgentToolResult execute(ToolInvocation invocation) throws ToolExecutionException {
        try {
            JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
            String text = arguments.path("text").asText(null);
            if (text == null || text.isBlank()) {
                return AgentToolResult.error("text 参数不能为空（要说什么就写什么）");
            }
            speeches.add(new ShadowSpeech(text,
                    arguments.path("replyToMessageId").isTextual()
                            ? arguments.path("replyToMessageId").asText() : null));
            // 成功形态与真 sendMessage 一致（模型侧无从分辨）——差异只在出口被拦截
            return new AgentToolResult(
                    List.of(new cn.edu.scut.chatbot.loop.message.TextContent(
                            "已发送（影子重放，仅计入重放报告）")),
                    false, false, java.util.Map.of("outboxId", "shadow"));
        } catch (Exception e) {
            throw new ToolExecutionException("engine.tool.invalid_arguments",
                    "shadowSendMessage 参数解析失败", e);
        }
    }
}
