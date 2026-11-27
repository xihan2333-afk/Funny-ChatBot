package cn.edu.scut.chatbot.observability.replay;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 影子 createTask（eval-and-flywheel 契约第 4 节铁律二）：dry-run 重放中创建任务的
 * 替身——走<b>真实 Mandate 校验</b>（群策略允许与否的判定链原样执行，被拒路径
 * 同样留痕），Task 落库后立即取消（mandateRef 标记 replay 归属）——不进真实调度，
 * 行为记录进重放报告。
 *
 * <p>非 @Component：由 {@link ReplayToolAssembler} 在重放装配时按次构造。
 */
public class ShadowCreateTask implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ShadowCreateTask.class);

    private final ToolDescriptor delegateDescriptor;

    private final TaskCoordinator taskCoordinator;

    private final List<String> createdTasks = new ArrayList<>();

    public ShadowCreateTask(AgentTool realCreateTask, TaskCoordinator taskCoordinator) {
        this.delegateDescriptor = realCreateTask.descriptor();
        this.taskCoordinator = taskCoordinator;
    }

    /**
     * Run 结束后取走本次创建（并已取消）的任务 id 清单
     */
    public List<String> createdTasks() {
        return List.copyOf(createdTasks);
    }

    @Override
    public ToolDescriptor descriptor() {
        return delegateDescriptor;
    }

    @Override
    public AgentToolResult execute(ToolInvocation invocation) {
        try {
            JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
            TaskDO task = new TaskDO();
            task.setPlatform(invocation.platform());
            task.setBotId(invocation.botId());
            task.setGroupId(invocation.groupId());
            task.setTaskKind("heavy".equals(arguments.path("kind").asText())
                    ? TaskDO.KIND_HEAVY : TaskDO.KIND_LIGHT);
            task.setCategory(arguments.path("category").asText("general"));
            task.setMandateSource(TaskDO.MANDATE_MEMBER);
            task.setCreatorUserId(invocation.turnId() != null ? "viaTurn" : "viaTask");
            // replay 归属标记：调度侧与审计侧凭 mandateRef 前缀识别重放产物
            task.setMandateRef("replay: dry-run 重放创建（影子）");
            task.setSnapshot(arguments.path("instruction").asText());
            // 真实 Mandate 校验与落库（被拒路径与线上一致）
            long taskId = taskCoordinator.createTask(task);
            // 落库即停止——不进真实调度（到点扫描不会拾起终态任务）
            taskCoordinator.stopTask(taskId, "dry-run 影子任务不进真实调度");
            createdTasks.add(String.valueOf(taskId));
            return AgentToolResult.text("任务已创建（影子重放，taskId=" + taskId
                    + "，已标记不执行）；授权不通过会被拒绝留痕。");
        } catch (CommandRejectedException rejected) {
            // 真实校验被拒：与线上同形态返回（模型可自然告知群友）——重放报告记被拒
            return AgentToolResult.error("任务创建被拒绝：" + rejected.getReason());
        } catch (Exception e) {
            log.warn("影子 createTask 执行异常：{}", e.getMessage());
            return AgentToolResult.error("任务创建失败：" + e.getMessage());
        }
    }
}
