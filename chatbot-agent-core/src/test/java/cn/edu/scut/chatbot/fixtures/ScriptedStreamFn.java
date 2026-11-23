package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.loop.CancellationSignal;
import cn.edu.scut.chatbot.loop.ModelRequest;
import cn.edu.scut.chatbot.loop.ModelStreamEvent;
import cn.edu.scut.chatbot.loop.StreamConsumer;
import cn.edu.scut.chatbot.loop.StreamFn;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 剧本模型假件（research R9 能力清单）：按剧本序列吐十一种事件，支持注入——
 * 八种错误码（llm.*）、N 秒无增量（考卡死检测）、中途断流（半截 + Failed）、
 * 参数 JSON 截断、多工具交错分片、文本分片、思考增量。
 *
 * <p>宪法「模型是假件、按剧本演」是长期纪律：2.3 落地后本假件继续作为标准测试假件存在。
 * 确定性测试不依赖真模型的概率输出。
 */
public class ScriptedStreamFn implements StreamFn {

    /**
     * 剧本步骤：一个事件或一个注入动作（形态简单，普通接口足够）
     */
    public interface ScriptStep {

        /**
         * 吐一个事件
         */
        record Emit(ModelStreamEvent event) implements ScriptStep {
        }

        /**
         * 停顿指定毫秒（不吐事件——考卡死检测）
         */
        record Stall(long millis) implements ScriptStep {
        }

        /**
         * 逐步吐出之间的真实间隔（模拟慢速流）
         */
        record Sleep(long millis) implements ScriptStep {
        }
    }

    /**
     * 调用计数（断言「模型调用为零」等负向用例的观察口径）
     */
    private final AtomicInteger callCount = new AtomicInteger();

    private final List<List<ScriptStep>> scriptsPerCall = new ArrayList<>();

    /**
     * 追加一轮剧本（第 N 次 stream 调用执行第 N 个剧本；未配置剧本的调用吐自然结束空消息）
     */
    public ScriptedStreamFn addScript(List<ScriptStep> steps) {
        scriptsPerCall.add(new ArrayList<>(steps));
        return this;
    }

    public int callCount() {
        return callCount.get();
    }

    @Override
    public void stream(ModelRequest request, StreamConsumer consumer, CancellationSignal signal) {
        int callIndex = callCount.getAndIncrement();
        List<ScriptStep> script = callIndex < scriptsPerCall.size()
                ? scriptsPerCall.get(callIndex)
                : List.of(new ScriptStep.Emit(completedEmpty()));

        for (ScriptStep step : script) {
            if (signal.isCancelled()) {
                // 取消唯一通道：置位后停止读流，以 Failed(aborted) 终止（契约纪律）
                consumer.onEvent(new ModelStreamEvent.Failed("llm.aborted", "取消信号置位，停止读流"));
                return;
            }
            switch (step) {
                case ScriptStep.Emit emit -> consumer.onEvent(emit.event());
                case ScriptStep.Stall stall -> sleepUnlessCancelled(stall.millis(), signal);
                case ScriptStep.Sleep sleep -> sleepUnlessCancelled(sleep.millis(), signal);
                default -> throw new IllegalStateException("未知剧本步骤：" + step);
            }
        }
        // 剧本走完但取消已置位（如卡死检测在停顿中触发）：以 aborted 终止——真实实现同样在读流侧感知取消
        if (signal.isCancelled()) {
            consumer.onEvent(new ModelStreamEvent.Failed("llm.aborted", "取消信号置位，停止读流"));
        }
    }

    private static ModelStreamEvent completedEmpty() {
        return new ModelStreamEvent.Completed(new AssistantMessage(
                "scripted-" + System.nanoTime(), java.time.Instant.now(), 60, null,
                "（剧本兜底空消息）", List.of(), AssistantMessage.STOP_REASON_STOP,
                AssistantMessage.TokenUsage.ZERO));
    }

    /**
     * 可感知取消的停顿：周期醒来检查信号（真实实现是读流阻塞中被取消唤醒，行为等价）
     */
    private void sleepUnlessCancelled(long millis, CancellationSignal signal) {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < deadline && !signal.isCancelled()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ===== 剧本构造工具（测试用例的直白表达面） =====

    public static List<ScriptStep> naturalText(String text) {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextStart()));
        for (String chunk : text.split("(?<=\\G.{5})")) {
            steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextDelta(chunk)));
        }
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextEnd()));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.Completed(completed(text, List.of()))));
        return steps;
    }

    public static List<ScriptStep> toolCallThen(String callId, String toolName, String argumentsJson) {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallStart(0, callId, toolName)));
        // 参数 JSON 分两半吐（考增量归并）
        String json = argumentsJson;
        int mid = json.length() / 2;
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallDelta(0, json.substring(0, mid))));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallDelta(0, json.substring(mid))));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallEnd(0)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.Completed(completed(
                "", List.of(new AssistantMessage.ToolCallSegment(callId, toolName, argumentsJson))))));
        return steps;
    }

    /**
     * 多工具交错分片：两个工具调用的参数分片交替到达（考 index 归并）
     */
    public static List<ScriptStep> interleavedToolCalls(String callIdA, String toolA, String argsA,
                                                        String callIdB, String toolB, String argsB) {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallStart(0, callIdA, toolA)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallStart(1, callIdB, toolB)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallDelta(0, argsA.substring(0, argsA.length() / 2))));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallDelta(1, argsB.substring(0, argsB.length() / 2))));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallDelta(0, argsA.substring(argsA.length() / 2))));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallDelta(1, argsB.substring(argsB.length() / 2))));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallEnd(0)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallEnd(1)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.Completed(completed("", List.of(
                new AssistantMessage.ToolCallSegment(callIdA, toolA, argsA),
                new AssistantMessage.ToolCallSegment(callIdB, toolB, argsB))))));
        return steps;
    }

    /**
     * 中途断流：半截文本 + Failed（半截内容保留转录带错误标记的既定场景）
     */
    public static List<ScriptStep> brokenStreamHalfText(String partialText, String errorCode) {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextStart()));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextDelta(partialText)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.Failed(errorCode, "连接中断（剧本注入）")));
        return steps;
    }

    /**
     * 参数 JSON 截断：工具调用参数只吐一半就 Completed（考 prepare 段拒绝执行）
     */
    public static List<ScriptStep> truncatedToolArguments(String callId, String toolName, String truncatedJson) {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallStart(0, callId, toolName)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallDelta(0, truncatedJson)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ToolCallEnd(0)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.Completed(completed("", List.of(
                new AssistantMessage.ToolCallSegment(callId, toolName, truncatedJson))))));
        return steps;
    }

    /**
     * 思考增量 + 文本（思考内容不进转录、只作活跃信号）
     */
    public static List<ScriptStep> thinkingThenText(String thinking, String text) {
        List<ScriptStep> steps = new ArrayList<>();
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ThinkingStart()));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ThinkingDelta(thinking)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.ThinkingEnd()));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextStart()));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextDelta(text)));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.TextEnd()));
        steps.add(new ScriptStep.Emit(new ModelStreamEvent.Completed(completed(text, List.of()))));
        return steps;
    }

    /**
     * N 秒无增量（考卡死检测：AgentLoop 的 stall watcher 置取消信号）
     */
    public static List<ScriptStep> stallForever(long millis) {
        return List.of(new ScriptStep.Stall(millis));
    }

    /**
     * 直接失败（网络/超时/限流类错误码）
     */
    public static List<ScriptStep> immediateFailure(String errorCode, String message) {
        return List.of(new ScriptStep.Emit(new ModelStreamEvent.Failed(errorCode, message)));
    }

    private static AssistantMessage completed(String text,
                                              List<AssistantMessage.ToolCallSegment> toolCalls) {
        String stopReason = toolCalls.isEmpty()
                ? AssistantMessage.STOP_REASON_STOP : AssistantMessage.STOP_REASON_TOOL_USE;
        return new AssistantMessage(
                "scripted-" + System.nanoTime(), java.time.Instant.now(), 60, null,
                text, toolCalls, stopReason, AssistantMessage.TokenUsage.ZERO);
    }
}
