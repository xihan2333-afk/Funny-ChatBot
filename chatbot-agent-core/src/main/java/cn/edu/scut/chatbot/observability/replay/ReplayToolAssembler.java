package cn.edu.scut.chatbot.observability.replay;

import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.tool.assembler.ToolsetAssembler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 影子工具装配（eval-and-flywheel 契约第 4 节、T054）：dry-run 重放的工具集出口
 * 改造——在 {@link ToolsetAssembler} 真实装配（群配置禁用、形态裁配等全部生效）之上，
 * 把发言与任务两个副作用出口替换为影子实现。
 *
 * <p>替换面口径：重放用 TURN 形态（快工具池，无 Worker 慢工具在场）——群侧副作用的
 * 唯一出口是 sendMessage（宪法第三条），拦截它即拦住全部对外发言；createTask 走
 * 真实 Mandate 校验但影子取消。读类工具（fetchWeb / readSkill 等）无群副作用，
 * 原样真实执行——上下文与线上同构。
 *
 * <p>装配产物 {@link ShadowToolset} 持有影子实例引用，Run 结束后从中取发言清单
 * 与任务清单进 ReplayReport。
 */
@Component
public class ReplayToolAssembler {

    public static final String TOOL_SEND_MESSAGE = "sendMessage";
    public static final String TOOL_CREATE_TASK = "createTask";

    private final ToolsetAssembler toolsetAssembler;
    private final TaskCoordinator taskCoordinator;

    public ReplayToolAssembler(ToolsetAssembler toolsetAssembler, TaskCoordinator taskCoordinator) {
        this.toolsetAssembler = toolsetAssembler;
        this.taskCoordinator = taskCoordinator;
    }

    /**
     * 影子化工具集：真实装配 → 出口替换
     */
    public ShadowToolset assemble(String platform, String botId, String groupId) {
        List<AgentTool> assembled = toolsetAssembler.assemble(
                RunConfig.ExecutionForm.TURN, platform, botId, groupId);
        List<AgentTool> shadowed = new ArrayList<>();
        ShadowSendMessage sendShadow = null;
        ShadowCreateTask taskShadow = null;
        for (AgentTool tool : assembled) {
            String name = tool.descriptor().name();
            if (TOOL_SEND_MESSAGE.equals(name)) {
                sendShadow = new ShadowSendMessage(tool);
                shadowed.add(sendShadow);
            } else if (TOOL_CREATE_TASK.equals(name)) {
                taskShadow = new ShadowCreateTask(tool, taskCoordinator);
                shadowed.add(taskShadow);
            } else {
                shadowed.add(tool);
            }
        }
        if (sendShadow == null) {
            // sendMessage 不在工具集（群禁用等）——重放无发言出口，报告将为空发言
            sendShadow = new ShadowSendMessage(new NoOpSendTool());
        }
        return new ShadowToolset(List.copyOf(shadowed), sendShadow, taskShadow);
    }

    /**
     * 装配产物：工具列表（进 RunSpec）+ 影子实例（报告取数）
     */
    public record ShadowToolset(List<AgentTool> tools,
                                ShadowSendMessage sendShadow,
                                ShadowCreateTask taskShadow) {
    }

    /**
     * 工具集不含 sendMessage 时的空壳（descriptor 仅供 ShadowSendMessage 构造，
     * 不会被调用）
     */
    private static final class NoOpSendTool implements AgentTool {

        @Override
        public cn.edu.scut.chatbot.loop.ToolDescriptor descriptor() {
            return new cn.edu.scut.chatbot.loop.ToolDescriptor(
                    TOOL_SEND_MESSAGE, "（重放缺席兜底）", "{}",
                    cn.edu.scut.chatbot.loop.ToolDescriptor.ToolSpeed.FAST,
                    cn.edu.scut.chatbot.loop.ToolDescriptor.EffectKind.GROUP_SIDE_EFFECT,
                    cn.edu.scut.chatbot.loop.ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public cn.edu.scut.chatbot.loop.AgentToolResult execute(
                cn.edu.scut.chatbot.loop.ToolInvocation invocation) {
            throw new IllegalStateException("重放兜底 sendMessage 不应被调用");
        }
    }
}
