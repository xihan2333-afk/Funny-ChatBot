package cn.edu.scut.chatbot.tool.builtin;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.loop.AgentTool;
import cn.edu.scut.chatbot.loop.AgentToolResult;
import cn.edu.scut.chatbot.loop.ToolDescriptor;
import cn.edu.scut.chatbot.loop.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 引擎耦合工具三件（T074；sendMessage 独立文件）：createTask / cancelTask / queryTasks——
 * 模型的任务侧动作全部经 TaskCoordinator 门面（engine 命令，Mandate 校验在门面内）。
 */
public final class TaskTools {

    private TaskTools() {
    }

    @Component
    static class CreateTaskTool implements AgentTool {

        private final TaskCoordinator taskCoordinator;

        CreateTaskTool(TaskCoordinator taskCoordinator) {
            this.taskCoordinator = taskCoordinator;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "createTask",
                    "创建一个后台任务（定时发言、延迟提醒、生图调研等长程行为）。"
                            + "轻任务（单轮发言）选 light；需要多轮工作或长时等待选 heavy（后台 Worker 执行）。",
                    SchemaBuilder.create()
                            .string("kind", "light 单轮发言 / heavy 后台多轮任务")
                            .string("category", "行为类别：daily_greeting / remind / research / image 等")
                            .string("instruction", "任务指令（要做什么、什么口径）")
                            .string("scheduleAt", "执行时间（可空=立即；ISO 时间或相对描述如'明天早上'）")
                            .require("kind").require("category").require("instruction").build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.TASK_SIDE_EFFECT,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            try {
                JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
                TaskDO task = new TaskDO();
                task.setPlatform(invocation.platform());
                task.setBotId(invocation.botId());
                task.setGroupId(invocation.groupId());
                task.setTaskKind("heavy".equals(arguments.path("kind").asText()) ? TaskDO.KIND_HEAVY : TaskDO.KIND_LIGHT);
                task.setCategory(arguments.path("category").asText("general"));
                // 工具发起的任务：创建者记为群（member 授权须群策略允许，不过则拒绝并留痕）
                task.setMandateSource(TaskDO.MANDATE_MEMBER);
                task.setCreatorUserId(invocation.turnId() != null ? "viaTurn" : "viaTask");
                task.setMandateRef("由模型在对话中发起");
                task.setSnapshot(arguments.path("instruction").asText());
                long taskId = taskCoordinator.createTask(task);
                return AgentToolResult.text("任务已创建（taskId=" + taskId
                        + "），到点会自动执行；授权不通过会被拒绝留痕。");
            } catch (CommandRejectedException rejected) {
                return AgentToolResult.error("任务创建被拒绝：" + rejected.getReason());
            } catch (Exception e) {
                return AgentToolResult.error("任务创建失败：" + e.getMessage());
            }
        }
    }

    @Component
    static class CancelTaskTool implements AgentTool {

        private final TaskCoordinator taskCoordinator;

        CancelTaskTool(TaskCoordinator taskCoordinator) {
            this.taskCoordinator = taskCoordinator;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "cancelTask",
                    "取消一个后台任务。",
                    SchemaBuilder.create().string("taskId", "要取消的任务 ID").require("taskId").build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.TASK_SIDE_EFFECT,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            try {
                JsonNode arguments = JsonMappers.shared().readTree(invocation.argumentsJson());
                long taskId = arguments.path("taskId").asLong();
                taskCoordinator.stopTask(taskId, "模型在对话中取消");
                return AgentToolResult.text("任务已取消（taskId=" + taskId + "）");
            } catch (CommandRejectedException rejected) {
                return AgentToolResult.error("取消被拒绝：" + rejected.getReason());
            } catch (Exception e) {
                return AgentToolResult.error("任务取消失败：" + e.getMessage());
            }
        }
    }

    @Component
    static class QueryTasksTool implements AgentTool {

        private final TaskMapper taskMapper;

        QueryTasksTool(TaskMapper taskMapper) {
            this.taskMapper = taskMapper;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    "queryTasks",
                    "查询本群的后台任务列表与状态。",
                    SchemaBuilder.create().build(),
                    ToolDescriptor.ToolSpeed.FAST,
                    ToolDescriptor.EffectKind.PURE_QUERY,
                    ToolDescriptor.ToolSource.BUILTIN,
                    false);
        }

        @Override
        public AgentToolResult execute(ToolInvocation invocation) {
            List<TaskDO> tasks = taskMapper.selectByGroup(
                    invocation.platform(), invocation.botId(), invocation.groupId());
            StringBuilder view = new StringBuilder("本群任务（最新在前，最多列 10 条）：\n");
            tasks.stream().limit(10).forEach(task -> view
                    .append("- #").append(task.getId()).append(' ').append(task.getCategory())
                    .append("（").append(task.getStatus()).append("）")
                    .append(task.getTaskKind()).append('\n'));
            if (tasks.isEmpty()) {
                view.append("（暂无任务）");
            }
            return AgentToolResult.text(view.toString());
        }
    }
}
