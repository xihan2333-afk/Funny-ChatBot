package cn.edu.scut.chatbot.loop;

/**
 * 工具静态声明（data-model 第 5 节）：注册后运行中不变（要变即新配置刷新）。
 * 三维分类元数据：快慢（装配第二步过滤）、副作用类别、来源。
 */
public record ToolDescriptor(
        String name,
        String description,
        String parametersJsonSchema,
        ToolSpeed speed,
        EffectKind effectKind,
        ToolSource source,
        boolean parallelizable
) {

    /**
     * 快慢维度：Turn/轻任务形态只装配 fast 工具；Worker 全量
     */
    public enum ToolSpeed {
        FAST, SLOW
    }

    /**
     * 副作用类别（观测与审计的归类口径）
     */
    public enum EffectKind {
        /**
         * 纯查询（无副作用）
         */
        PURE_QUERY,
        /**
         * 群副作用（发言/表态等）
         */
        GROUP_SIDE_EFFECT,
        /**
         * 任务副作用（建任务/取消任务）
         */
        TASK_SIDE_EFFECT,
        /**
         * 本地执行（跑代码等）
         */
        LOCAL_EXECUTION,
        /**
         * 自身状态（情绪等）
         */
        SELF_STATE,
        /**
         * 外部 API（抓网页/泛化工具）
         */
        EXTERNAL_API
    }

    /**
     * 来源：内置实现 / HTTP 泛化配置渲染
     */
    public enum ToolSource {
        BUILTIN, HTTP_GENERIC
    }
}
