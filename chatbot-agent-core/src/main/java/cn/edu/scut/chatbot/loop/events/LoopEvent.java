package cn.edu.scut.chatbot.loop.events;

import cn.edu.scut.chatbot.loop.RunConfig;

import java.util.List;

/**
 * 循环事件契约（契约 loop-kernel.md 第 3 节）：run/turn/message/tool 四组，仅供观察
 * （可观测性数据源；链路追踪、成本统计走事件监听不写钩子）。事件在固定点位发射。
 *
 * <p>监听器两条纪律（结构保证非自觉）：只做快速入队（监听器外壳有界队列，满则丢弃计数；
 * 慢操作由后台消费者消化）；异常不外溢（框架捕获记告警，不影响循环）。
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
     * Run 级事件：启动（形态）与结束（结局四形态 + 发言清单或沉默原因）
     */
    record RunEvent(
            long runId,
            Phase phase,
            RunConfig.ExecutionForm form,
            String stopReason,
            List<String> speechOutboxIds,
            String silentReason
    ) implements LoopEvent {

        public static RunEvent started(long runId, RunConfig.ExecutionForm form) {
            return new RunEvent(runId, Phase.START, form, null, List.of(), null);
        }

        public static RunEvent ended(long runId, String stopReason, List<String> speechOutboxIds, String silentReason) {
            return new RunEvent(runId, Phase.END, null, stopReason, speechOutboxIds, silentReason);
        }

        @Override
        public Group group() {
            return Group.RUN;
        }
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
     * Message 级事件：消息产出过程（文本/思考增量的 update 不逐条发——按段聚合，
     * 观测面只需要「有进展」）
     */
    record MessageEvent(
            long runId,
            Phase phase,
            String messageId
    ) implements LoopEvent {

        @Override
        public Group group() {
            return Group.MESSAGE;
        }
    }

    /**
     * Tool 级事件：工具执行开始与结束（结束带 isError 归一化结果）
     */
    record ToolEvent(
            long runId,
            Phase phase,
            String callId,
            String toolName,
            boolean isError
    ) implements LoopEvent {

        @Override
        public Group group() {
            return Group.TOOL;
        }
    }
}
