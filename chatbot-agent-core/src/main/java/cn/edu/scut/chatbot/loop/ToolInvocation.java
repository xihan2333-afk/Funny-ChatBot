package cn.edu.scut.chatbot.loop;

/**
 * 工具调用入参（data-model 第 5 节）：被调工具名、调用参数 JSON、群标识三元组、
 * 执行形态、发起来源。工具名供 beforeToolCall 安全闸按 effectKind 分派校验矩阵。
 */
public record ToolInvocation(
        String argumentsJson,
        String platform,
        String botId,
        String groupId,
        RunConfig.ExecutionForm form,
        Long turnId,
        Long taskId,
        String toolName
) {

    /**
     * 兼容形态（无工具名——工具实现侧自组调用时用；安全闸对无名调用放行）
     */
    public ToolInvocation(String argumentsJson, String platform, String botId, String groupId,
                          RunConfig.ExecutionForm form, Long turnId, Long taskId) {
        this(argumentsJson, platform, botId, groupId, form, turnId, taskId, null);
    }
}
