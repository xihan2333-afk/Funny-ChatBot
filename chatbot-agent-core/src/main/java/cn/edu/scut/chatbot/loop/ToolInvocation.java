package cn.edu.scut.chatbot.loop;

/**
 * 工具调用入参（data-model 第 5 节）：调用参数 JSON、群标识三元组、执行形态、发起来源。
 */
public record ToolInvocation(
        String argumentsJson,
        String platform,
        String botId,
        String groupId,
        RunConfig.ExecutionForm form,
        Long turnId,
        Long taskId
) {
}
