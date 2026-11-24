package cn.edu.scut.chatbot.loop;

import java.time.Duration;

/**
 * 三形态差异配置（data-model 第 3 节表格）：Run 启动时以不可变对象组装。
 *
 * <table>
 *   <tr><th>配置</th><th>Turn 生成</th><th>轻任务执行</th><th>Worker 执行</th></tr>
 *   <tr><td>轮次预算</td><td>2~3 收敛</td><td>3~5</td><td>高上限 + 硬顶（超限优雅收敛）</td></tr>
 *   <tr><td>时长预算</td><td>秒级</td><td>十秒级</td><td>分钟级</td></tr>
 *   <tr><td>工具集</td><td>快工具 + sendMessage</td><td>同 Turn</td><td>全量（无 sendMessage）</td></tr>
 *   <tr><td>systemPrompt 层次</td><td>身份+群上下文+能力协议（含发言协议）</td><td>同左 + 任务层</td><td>能力协议（无发言协议）+ 任务执行提示词</td></tr>
 * </table>
 *
 * <p>工具集快照本体在 {@link AgentContext#toolNames()}（装配器产出）；
 * 本 record 只携带形态与预算——钩子按形态差异化行为。
 */
public record RunConfig(
        ExecutionForm form,
        int maxTurns,
        Duration timeBudget,
        long stallTimeoutMillis
) {

    /**
     * 执行形态（三形态）——形态决定工具集裁配、提示词层次、transformContext 注入内容
     */
    public enum ExecutionForm {
        /**
         * Turn 生成（被动回应）：快工具 + sendMessage
         */
        TURN,
        /**
         * 轻任务执行（单轮生成发言）：同 Turn 工具集
         */
        LIGHT_TASK,
        /**
         * Worker 执行（重任务隔离）：全量工具无 sendMessage、任务快照上下文、高预算
         */
        WORKER
    }

    public static RunConfig turnDefaults() {
        return new RunConfig(ExecutionForm.TURN, 3, Duration.ofSeconds(60), 15_000);
    }

    public static RunConfig lightTaskDefaults() {
        return new RunConfig(ExecutionForm.LIGHT_TASK, 5, Duration.ofSeconds(120), 15_000);
    }

    public static RunConfig workerDefaults() {
        return new RunConfig(ExecutionForm.WORKER, 40, Duration.ofMinutes(10), 30_000);
    }
}
