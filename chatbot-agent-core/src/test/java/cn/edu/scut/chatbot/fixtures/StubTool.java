package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.exception.ToolExecutionException;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 测试假工具：结果可剧本化、调用可观测（顺序与次数）。
 */
public class StubTool implements AgentTool {

    private final ToolDescriptor descriptor;

    private final Function<ToolInvocation, AgentToolResult> behavior;

    private final List<ToolInvocation> receivedInvocations = new CopyOnWriteArrayList<>();

    public StubTool(String name) {
        this(name, invocation -> AgentToolResult.text("工具 " + name + " 执行成功"));
    }

    public StubTool(String name, Function<ToolInvocation, AgentToolResult> behavior) {
        this.descriptor = new ToolDescriptor(
                name, "测试工具 " + name, "{\"type\":\"object\",\"properties\":{}}",
                ToolDescriptor.ToolSpeed.FAST, ToolDescriptor.EffectKind.PURE_QUERY,
                ToolDescriptor.ToolSource.BUILTIN, false);
        this.behavior = behavior;
    }

    public static StubTool failing(String name, String errorMessage) {
        return new StubTool(name, invocation -> {
            throw new ToolExecutionException("tool.execution_failed", errorMessage);
        });
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AgentToolResult execute(ToolInvocation invocation) throws ToolExecutionException {
        receivedInvocations.add(invocation);
        return behavior.apply(invocation);
    }

    public List<ToolInvocation> receivedInvocations() {
        return receivedInvocations;
    }

    public int callCount() {
        return receivedInvocations.size();
    }

    /**
     * sendMessage 形态假工具：返回带 outboxId 附件的正常结果（发言交付判定的输入）
     */
    public static StubTool sendMessageTool() {
        return new StubTool("sendMessage", invocation -> {
            String outboxId = "ob-" + System.nanoTime();
            return new AgentToolResult(
                    List.of(new cn.edu.scut.chatbot.loop.message.TextContent("已发送")),
                    false, false, java.util.Map.of("outboxId", outboxId));
        });
    }
}
