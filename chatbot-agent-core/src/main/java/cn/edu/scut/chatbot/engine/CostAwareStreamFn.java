package cn.edu.scut.chatbot.engine;

import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.slot.CostRecorder;
import cn.edu.scut.chatbot.llm.slot.CostSample;
import cn.edu.scut.chatbot.loop.*;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;

/**
 * 落账装饰器（research R8 决策：装饰器方案——loop 契约不被观测需求污染）：包装 StreamFn
 * 观察 Completed/Failed 终止事件与首个增量事件（TTFT 口径 = 请求发出到首个 Delta）产出
 * CostSample，连同 LlmCallMeta 与 ModelRef 交 CostRecorder 异步落账。
 *
 * <p>每个 Run 组装时以闭包绑定该 Run 的 meta（RunSpec 持 streamFn 的既有形态——
 * GenerationRunner / WorkerRunner 装配）；直连调用（callOnce / 三能力接口）经网关
 * API 显式传 meta 走同一 CostRecorder。观察失败不影响流本身（装饰器绝不抛异常）。
 */
public final class CostAwareStreamFn implements StreamFn {

    private final StreamFn delegate;

    private final CostRecorder recorder;

    private final LlmCallMeta meta;

    public CostAwareStreamFn(StreamFn delegate, CostRecorder recorder, LlmCallMeta meta) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.meta = meta;
    }

    @Override
    public void stream(ModelRequest request, StreamConsumer consumer, CancellationSignal signal) {
        long startNanos = System.nanoTime();
        Observation observation = new Observation();
        try {
            delegate.stream(request, event -> {
                observation.onEvent(event, startNanos);
                consumer.onEvent(event);
            }, signal);
        } finally {
            recordQuietly(request, observation, startNanos);
        }
    }

    private void recordQuietly(ModelRequest request, Observation observation, long startNanos) {
        try {
            long totalMs = elapsedMillis(startNanos);
            recorder.record(meta, request.model(),
                    new CostSample(observation.usage, observation.ttftMillis(startNanos), totalMs,
                            observation.stopReason, observation.errorCode));
        } catch (Exception e) {
            // 计量失败不影响调用结果
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 流观察累积（单次调用局部——无状态装饰器实例级的纪律靠调用隔离保证）
     */
    private static final class Observation {

        private long firstDeltaNanos = -1;

        private AssistantMessage.TokenUsage usage;

        private String stopReason;

        private String errorCode;

        void onEvent(ModelStreamEvent event, long startNanos) {
            if (firstDeltaNanos < 0 && isIncrement(event)) {
                firstDeltaNanos = System.nanoTime();
            }
            if (event instanceof ModelStreamEvent.Completed completed) {
                this.usage = completed.message().usage();
                this.stopReason = completed.message().stopReason();
            } else if (event instanceof ModelStreamEvent.Failed failed) {
                this.stopReason = failed.errorCode() != null && failed.errorCode().endsWith("aborted")
                        ? AssistantMessage.STOP_REASON_ABORTED : AssistantMessage.STOP_REASON_ERROR;
                this.errorCode = failed.errorCode();
            }
        }

        private static boolean isIncrement(ModelStreamEvent event) {
            return event instanceof ModelStreamEvent.ThinkingDelta
                    || event instanceof ModelStreamEvent.TextDelta
                    || event instanceof ModelStreamEvent.ToolCallDelta;
        }

        long ttftMillis(long startNanos) {
            return firstDeltaNanos < 0 ? 0 : (firstDeltaNanos - startNanos) / 1_000_000;
        }
    }
}
