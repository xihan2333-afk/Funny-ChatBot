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
        long stallTimeoutMillis,
        VersionKeys versionKeys
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

    /**
     * 版本三键（observability-bridge 契约 2.2 节）：RunConfig 组装时的当前生效版本，
     * 随 RunEvent START 挂到 run Span（chatbot.version.*）——重放批次"按版本分组聚合
     * 得注入前后对照"的口径来源。纯值三 long，loop 不依赖任何业务包。
     *
     * <p>三键语义：promptFragment = 本次组装用到的 published 片段最大版本号；
     * persona = 绑定人格的 version_no（未绑定为 0）；slotBinding = 槽位行 updated_at
     * 的纪元毫秒（绑定关系无自增版本列，变更时间即版本）。
     */
    public record VersionKeys(long promptFragment, long persona, long slotBinding) {
    }

    public static RunConfig turnDefaults() {
        return new RunConfig(ExecutionForm.TURN, 3, Duration.ofSeconds(60), 15_000, null);
    }

    public static RunConfig lightTaskDefaults() {
        return new RunConfig(ExecutionForm.LIGHT_TASK, 5, Duration.ofSeconds(120), 15_000, null);
    }

    public static RunConfig workerDefaults() {
        return new RunConfig(ExecutionForm.WORKER, 40, Duration.ofMinutes(10), 30_000, null);
    }

    /**
     * 组装期填入版本三键（engine 侧在 RunSpec 组装点调用——观测代码零侵入 loop 内核）
     */
    public RunConfig withVersionKeys(VersionKeys keys) {
        return new RunConfig(form, maxTurns, timeBudget, stallTimeoutMillis, keys);
    }
}
