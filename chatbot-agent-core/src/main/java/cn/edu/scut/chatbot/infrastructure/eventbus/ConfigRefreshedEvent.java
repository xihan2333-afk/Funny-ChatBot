package cn.edu.scut.chatbot.infrastructure.eventbus;

/**
 * 进程内统一配置刷新事件：由管理面配置发布、参数热更新或回滚操作发出，
 * 通知工具注册表、模型目录、提示词/技能缓存等刷新内存快照（进行中 Run 持旧快照不受扰）。
 *
 * @param entityKind 变更实体类型（如 fragment / persona / skill / tool_config / http_tool_config / model / system_param / all）
 * @param reason     触发原因说明
 */
public record ConfigRefreshedEvent(String entityKind, String reason) {

    public ConfigRefreshedEvent(String reason) {
        this("all", reason);
    }
}
