package cn.edu.scut.chatbot.loop.events;

import cn.edu.scut.chatbot.loop.RunConfig;

import java.util.List;

/**
 * 循环事件契约（契约 loop-kernel.md 第 3 节）：run/turn/message/tool 四组，仅供观察
 * （可观测性数据源；链路追踪、成本统计走事件监听不写钩子）。事件在固定点位发射。
 *
 * <p>监听器两条纪律（结构保证非自觉）：只做快速入队（监听器外壳有界队列，满则丢弃计数；
 * 慢操作由后台消费者消化）；异常不外溢（框架捕获记告警，不影响循环）。
 *
 * <p>005 用量与参数摘要扩展（observability-bridge 契约 2.1 节）：MessageEvent END 携带
 * {@link UsageSummary} 纯值用量（与 llm_call_log 落账同源——两处消费同一流事件），
 * ToolEvent END 携带参数摘要与发言类 outboxId，RunEvent START 携带版本三键
 * （RunConfig 组装时填充）。字段全部是 String / long / boolean——loop 保持零业务依赖。
 */
public sealed interface LoopEvent permits
        LoopEvent.RunEvent,
        LoopEvent.TurnEvent,
        LoopEvent.MessageEvent,
        LoopEvent.ToolEvent {

    /**
     * 事件组：run / turn / message / tool
     */
    enum Group {RUN, TURN, MESSAGE, TOOL}

    /**
     * 阶段：start / update / end
     */
    enum Phase {START, UPDATE, END}

    long runId();

    Group group();

    Phase phase();

    /**
     * Run 级事件：启动（形态 + 版本三键 + 群身份）与结束（结局四形态 + 发言清单或沉默原因）
     */
    record RunEvent(
            long runId,
            Phase phase,
            RunConfig.ExecutionForm form,
            String stopReason,
            List<String> speechOutboxIds,
            String silentReason,
            RunConfig.VersionKeys versionKeys,
            RunIdentity identity,
            boolean replay
    ) implements LoopEvent {

        /**
         * START 携带群身份（观测桥把 run 及其子 Span 的 chatbot.group.* / turn_id /
         * task_id 公共属性挂上——翻译表「公共属性取自事件上下文」的唯一来源）；
         * replay 标记随 START 供 run Span 挂 chatbot.replay（T055 记账贯通）
         */
        public static RunEvent started(long runId, RunConfig.ExecutionForm form,
                                       RunConfig.VersionKeys versionKeys, RunIdentity identity,
                                       boolean replay) {
            return new RunEvent(runId, Phase.START, form, null, List.of(), null, versionKeys,
                    identity, replay);
        }

        public static RunEvent ended(long runId, String stopReason, List<String> speechOutboxIds, String silentReason) {
            return new RunEvent(runId, Phase.END, null, stopReason, speechOutboxIds, silentReason, null, null, false);
        }

        @Override
        public Group group() {
            return Group.RUN;
        }
    }

    /**
     * Run 的群身份（RunSpec 组装点已知——纯值五字段，loop 零业务依赖不变）。
     * turnId / taskId 可空（交付类与内部重放形态）。
     */
    record RunIdentity(
            String platform,
            String botId,
            String groupId,
            Long turnId,
            Long taskId
    ) {
    }

    /**
     * Turn 级事件：轮次开始与结束（轮次 = 一次模型调用 + 工具批次执行）
     */
    record TurnEvent(
            long runId,
            Phase phase,
            int turnIndex
    ) implements LoopEvent {

        @Override
        public Group group() {
            return Group.TURN;
        }
    }

    /**
     * Message 级事件：模型调用完成的用量报告（END 相位，每轮一次；失败调用同样发射——
     * 失败也耗成本，与 llm_call_log「失败调用也落账」同一口径）。
     */
    record MessageEvent(
            long runId,
            Phase phase,
            String messageId,
            UsageSummary usage
    ) implements LoopEvent {

        /**
         * 成功完成（Completed）：usage 从流终止事件就地取数
         */
        public static MessageEvent completed(long runId, String messageId, UsageSummary usage) {
            return new MessageEvent(runId, Phase.END, messageId, usage);
        }

        /**
         * 失败终止（Failed）：messageId 为空（无产出条目或仅残缺条目）
         */
        public static MessageEvent failed(long runId, UsageSummary usage) {
            return new MessageEvent(runId, Phase.END, null, usage);
        }

        @Override
        public Group group() {
            return Group.MESSAGE;
        }
    }

    /**
     * 模型调用用量摘要（纯值 record——事件桥映射 llm.call Span 属性；字段口径与
     * llm_call_log 落账列一一对应，SC-002 一致率断言的比对面）。失败调用 token 三值
     * 尽力保留（0 = 未知），errorCode 携带八种归一化错误码之一。
     */
    record UsageSummary(
            String modelId,
            String slotCode,
            String providerCode,
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long ttftMs,
            String stopReason,
            String errorCode
    ) {
    }

    /**
     * Tool 级事件：工具执行开始与结束。END 携带参数截断摘要（paramDigest，观测面
     * 排查用，不进转录）、发言类 outboxId（sendMessage 成功时的出事件 id——回执链路
     * 关联口径）与执行耗时。
     */
    record ToolEvent(
            long runId,
            Phase phase,
            String callId,
            String toolName,
            boolean isError,
            String paramDigest,
            String outboxId,
            long durationMs
    ) implements LoopEvent {

        /**
         * 参数摘要截断上限（观测面只需要「能认出这次调用了什么」，长参数无增益）
         */
        public static final int PARAM_DIGEST_LIMIT = 120;

        public static ToolEvent started(long runId, String callId, String toolName) {
            return new ToolEvent(runId, Phase.START, callId, toolName, false, null, null, 0);
        }

        public static ToolEvent ended(long runId, String callId, String toolName, boolean isError,
                                      String paramDigest, String outboxId, long durationMs) {
            return new ToolEvent(runId, Phase.END, callId, toolName, isError,
                    digest(paramDigest), outboxId, durationMs);
        }

        private static String digest(String argumentsJson) {
            if (argumentsJson == null) {
                return null;
            }
            return argumentsJson.length() <= PARAM_DIGEST_LIMIT
                    ? argumentsJson
                    : argumentsJson.substring(0, PARAM_DIGEST_LIMIT) + "…";
        }

        @Override
        public Group group() {
            return Group.TOOL;
        }
    }
}
