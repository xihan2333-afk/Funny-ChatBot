package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.exception.ToolExecutionException;
import cn.edu.scut.chatbot.loop.events.LoopEvent;
import cn.edu.scut.chatbot.loop.events.LoopEventListener;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;
import cn.edu.scut.chatbot.loop.transcript.SpeechOutcomeJudger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * AgentLoop：三种执行形态共用的推理发动机（设计方案 2.2.2、契约 loop-kernel.md）。
 *
 * <p>一个 while 循环加单一模型调用点（StreamFn 契约，绝不抛异常、一切失败编码为流内
 * 最终消息）；输入为 AgentContext 四要素快照；维护工作转录与新产生集合两份消息集合；
 * 轮次内执行工具三段式（prepare → execute → finalize）；轮次结束原子落库回调。
 *
 * <p>四条终止条件：模型消息不再含工具调用（自然结束）、stopReason=error/aborted
 * （异常终止）、工具批次全部 terminate（提前收敛）、shouldStopAfterTurn 预算超限
 * （优雅收敛）。钩子纪律（不得抛异常/返回空保持现状/改动只作用副本）由本类统一保障。
 *
 * <p>卡死检测：独立守护线程监控「最近增量时刻」，N 秒无任何增量（含思考增量——只作
 * 活跃信号不进转录）即置 CancellationSignal，StreamFn 置位后以 Failed(aborted) 终止。
 * 守护只置信号、不 interrupt 推理线程（取消唯一通道纪律）。
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Run 启动参数：全部依赖经此传入（loop 不持 Spring 上下文——内核纯净）。
     * idSupplier 发放转录条目 messageId（进程外可见 ID 由驱动方注入雪花发号器）。
     */
    public record RunSpec(
            long runId,
            String platform,
            String botId,
            String groupId,
            Long turnId,
            Long taskId,
            AgentContext context,
            RunConfig config,
            LoopHooks hooks,
            Map<String, AgentTool> tools,
            StreamFn streamFn,
            Clock clock,
            LongSupplier idSupplier,
            TurnPersister persister,
            LoopEventListener eventListener
    ) {
    }

    /**
     * 轮次落库回调（engine 实现：turn_end 时该轮全部消息一个事务写会话历史，
     * 崩溃最多丢当前未完成轮次）
     */
    public interface TurnPersister {

        void persistTurn(long runId, List<AgentMessage> turnMessages);
    }

    /**
     * Run 结局：停止原因 + 发言三结局（发言清单 / 兜底文本 / 沉默）+ 新产生集合
     */
    public record RunOutcome(
            String stopReason,
            List<String> speechOutboxIds,
            String fallbackText,
            boolean silent,
            List<AgentMessage> newMessages
    ) {

        public static final String STOP_NATURAL = "natural";
        public static final String STOP_ERROR = "error";
        public static final String STOP_ABORTED = "aborted";
        public static final String STOP_CONVERGED = "converged";
        public static final String STOP_BUDGET_EXCEEDED = "budgetExceeded";
    }

    /**
     * 执行一次 Run（阻塞至终态；调用方负责在专属虚拟线程上驱动）。
     */
    public RunOutcome execute(RunSpec spec) {
        RunConfig config = spec.config();
        List<AgentMessage> workingTranscript = new ArrayList<>(spec.context().messages());
        List<AgentMessage> newMessages = new ArrayList<>();
        CancellationSignal signal = CancellationSignal.create();
        Instant deadline = spec.clock().instant().plus(config.timeBudget());

        emit(spec, LoopEvent.RunEvent.started(spec.runId(), config.form()));

        AgentContext context = spec.context();
        String stopReason = RunOutcome.STOP_BUDGET_EXCEEDED;
        int completedTurns = 0;

        for (int turnIndex = 0; turnIndex < config.maxTurns(); turnIndex++) {
            emit(spec, new LoopEvent.TurnEvent(spec.runId(), LoopEvent.Phase.START, turnIndex));

            // 每轮调用前的钩子链：transformContext（最先）→ convertToLlm → resolveCredentials。
            // transformContext 的输入是工作转录当前态的副本（含上轮模型消息与工具结果）——
            // 钩子增删的现注条目（任务清单/当前时间）随副本直达投影，不落转录（每次调用重取）
            AgentContext turnView = new AgentContext(context.systemPrompt(),
                    List.copyOf(workingTranscript), context.tools(), context.params());
            context = safeTransformContext(spec, turnView);
            List<LlmMessage> llmMessages = safeConvertToLlm(spec, context.messages());
            String credential = safeResolveCredentials(spec);

            ModelRequest request = new ModelRequest(
                    llmMessages, context.systemPrompt(),
                    context.tools().stream().map(this::declareTool).toList(),
                    context.params(), credential);

            // 单一模型调用点：卡死守护 + 流事件收集
            StreamCollector collector = new StreamCollector(spec.clock(), spec.idSupplier());
            runWithStallWatch(spec, collector, signal,
                    () -> spec.streamFn().stream(request, collector, signal));

            AssistantMessage assistant = collector.takeCompleted();
            List<AgentMessage> turnMessages = new ArrayList<>();

            if (assistant == null) {
                // Failed 终止：半截内容保留转录且带错误标记，不投递不参与兜底
                AssistantMessage defective = collector.buildDefectiveMessage();
                if (defective != null) {
                    workingTranscript.add(defective);
                    newMessages.add(defective);
                    turnMessages.add(defective);
                }
                stopReason = collector.aborted() ? RunOutcome.STOP_ABORTED : RunOutcome.STOP_ERROR;
                safePersist(spec, turnMessages);
                break;
            }

            workingTranscript.add(assistant);
            newMessages.add(assistant);
            turnMessages.add(assistant);
            emit(spec, new LoopEvent.MessageEvent(spec.runId(), LoopEvent.Phase.END, assistant.messageId()));

            if (assistant.isDefective()) {
                // Completed 携带 error/aborted 标记（翻译器归一化的模型侧错误）
                stopReason = AssistantMessage.STOP_REASON_ABORTED.equals(assistant.stopReason())
                        ? RunOutcome.STOP_ABORTED : RunOutcome.STOP_ERROR;
                safePersist(spec, turnMessages);
                break;
            }

            if (assistant.toolCalls().isEmpty()) {
                // 自然结束：模型消息不再含工具调用
                stopReason = RunOutcome.STOP_NATURAL;
                safePersist(spec, turnMessages);
                completedTurns = turnIndex + 1;
                emit(spec, new LoopEvent.TurnEvent(spec.runId(), LoopEvent.Phase.END, turnIndex));
                break;
            }

            // 工具三段式执行（prepare 顺序 → execute 可并行 → finalize 定稿，按调用顺序入转录）
            List<ToolResultMessage> toolResults =
                    executeToolBatch(spec, assistant.toolCalls(), context, workingTranscript, newMessages);
            turnMessages.addAll(toolResults);

            // 轮次原子落库：本轮 assistant 消息 + 工具结果一个事务
            safePersist(spec, turnMessages);
            completedTurns = turnIndex + 1;
            emit(spec, new LoopEvent.TurnEvent(spec.runId(), LoopEvent.Phase.END, turnIndex));

            if (!toolResults.isEmpty() && toolResults.stream().allMatch(ToolResultMessage::terminate)) {
                // 全部 terminate 才收敛，混合批次照常继续
                stopReason = RunOutcome.STOP_CONVERGED;
                break;
            }
            if (safeShouldStop(spec, completedTurns, config)) {
                stopReason = RunOutcome.STOP_BUDGET_EXCEEDED;
                break;
            }
            if (spec.clock().instant().isAfter(deadline)) {
                stopReason = RunOutcome.STOP_BUDGET_EXCEEDED;
                break;
            }
            AgentContext prepared = safePrepareNextTurn(spec, context, config, completedTurns);
            if (prepared != null) {
                context = prepared;
            }
        }

        return finish(spec, stopReason, newMessages, completedTurns);
    }

    // =====================================================================
    // 工具三段式执行
    // =====================================================================

    private List<ToolResultMessage> executeToolBatch(RunSpec spec,
                                                     List<AssistantMessage.ToolCallSegment> toolCalls,
                                                     AgentContext context,
                                                     List<AgentMessage> workingTranscript,
                                                     List<AgentMessage> newMessages) {
        // prepare 段（永远顺序）：定位工具 → 参数基础校验 → beforeToolCall 拦截；
        // 不过关直接判错误结果不执行
        Map<Integer, ToolResultMessage> settled = new java.util.concurrent.ConcurrentHashMap<>();
        List<Integer> toExecute = new ArrayList<>();
        Map<Integer, ToolInvocation> invocations = new LinkedHashMap<>();

        for (int i = 0; i < toolCalls.size(); i++) {
            AssistantMessage.ToolCallSegment call = toolCalls.get(i);
            ToolResultMessage prepared = prepareOne(spec, i, call, invocations);
            if (prepared != null) {
                settled.put(i, prepared);
            } else {
                toExecute.add(i);
            }
        }

        // execute 段：全部声明 parallelizable 才并行（虚拟线程），否则顺序
        boolean parallel = !toExecute.isEmpty() && toExecute.stream().allMatch(i ->
                spec.tools().get(toolCalls.get(i).name()).descriptor().parallelizable());
        if (parallel && toExecute.size() > 1) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i : toExecute) {
                futures.add(CompletableFuture.runAsync(
                        () -> settled.put(i, executeOne(spec, toolCalls.get(i), invocations.get(i))),
                        runnable -> Thread.ofVirtual().name("loop-tool-exec").start(runnable)));
            }
            futures.forEach(CompletableFuture::join);
        } else {
            for (int i : toExecute) {
                settled.put(i, executeOne(spec, toolCalls.get(i), invocations.get(i)));
            }
        }

        // finalize 段：afterToolCall 逐字段覆盖（异常由框架捕获保持原结果）；
        // 结果一律按调用顺序入转录（并行完成顺序乱不影响写入顺序）
        List<ToolResultMessage> ordered = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolResultMessage result = settled.get(i);
            ToolResultMessage refined = safeAfterToolCall(spec, invocations.get(i), result);
            ToolResultMessage finalResult = refined != null ? refined : result;
            ordered.add(finalResult);
            workingTranscript.add(finalResult);
            newMessages.add(finalResult);
        }
        return ordered;
    }

    /**
     * prepare 单个调用：返回非 null 表示已定案（错误结果或拦截结果），不再进入 execute
     */
    private ToolResultMessage prepareOne(RunSpec spec, int index, AssistantMessage.ToolCallSegment call,
                                         Map<Integer, ToolInvocation> invocations) {
        AgentTool tool = spec.tools().get(call.name());
        if (tool == null) {
            return toolResult(spec, call,
                    AgentToolResult.error("工具不存在：" + call.name() + "（本次 Run 工具清单里没有它）"));
        }
        if (call.argumentsJson() == null || !isParsableJson(call.argumentsJson())) {
            // 参数 JSON 截断/坏：全部标记失败不执行（spec 边界值纪律）
            return toolResult(spec, call,
                    AgentToolResult.error("工具参数不是合法 JSON（可能被截断），已跳过执行"));
        }
        ToolInvocation invocation = new ToolInvocation(
                call.argumentsJson(), spec.platform(), spec.botId(), spec.groupId(),
                spec.config().form(), spec.turnId(), spec.taskId(), call.name());
        invocations.put(index, invocation);

        LoopHooks.BeforeToolCallHook.Interception interception =
                safeBeforeToolCall(spec, invocation);
        if (interception != null) {
            return toolResult(spec, call, interception.overrideResult());
        }
        return null;
    }

    /**
     * execute 单个调用：异常归一化 isError（错误文本不当 content 返回）
     */
    private ToolResultMessage executeOne(RunSpec spec, AssistantMessage.ToolCallSegment call,
                                         ToolInvocation invocation) {
        AgentTool tool = spec.tools().get(call.name());
        AgentToolResult result;
        emit(spec, new LoopEvent.ToolEvent(spec.runId(), LoopEvent.Phase.START,
                call.callId(), call.name(), false));
        try {
            result = tool.execute(invocation);
        } catch (ToolExecutionException e) {
            log.warn("工具执行失败 tool={} errorCode={}（归一化 isError 结果）",
                    call.name(), e.getErrorCode());
            result = AgentToolResult.error("工具执行失败：" + e.getMessage());
        } catch (Exception e) {
            log.warn("工具执行异常 tool={}（归一化 isError 结果）", call.name(), e);
            result = AgentToolResult.error("工具内部异常：" + e.getMessage());
        }
        ToolResultMessage message = toolResult(spec, call, result);
        emit(spec, new LoopEvent.ToolEvent(spec.runId(), LoopEvent.Phase.END,
                call.callId(), call.name(), message.isError()));
        return message;
    }

    private ToolResultMessage toolResult(RunSpec spec, AssistantMessage.ToolCallSegment call,
                                         AgentToolResult result) {
        return new ToolResultMessage(
                Long.toUnsignedString(spec.idSupplier().getAsLong()),
                spec.clock().instant(), 45, Long.toUnsignedString(spec.runId()),
                call.callId(), call.name(),
                result.content(), result.isError(), result.terminate(), result.attachments());
    }

    // =====================================================================
    // 卡死检测守护
    // =====================================================================

    /**
     * 守护线程：stallTimeoutMillis 内无任何增量（文本/思考/工具调用都算活跃）即置取消信号。
     * 只置信号不 interrupt——取消唯一通道纪律。流终止后守护随循环条件自然退出。
     */
    private void runWithStallWatch(RunSpec spec, StreamCollector collector,
                                   CancellationSignal signal, Runnable streamCall) {
        Thread.ofVirtual().name("loop-stall-watch-" + spec.runId()).start(() -> {
            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(spec.config().stallTimeoutMillis());
            while (!collector.terminated() && !signal.isCancelled()) {
                if (collector.nanosSinceLastActivity() > timeoutNanos) {
                    log.warn("模型流卡死检测触发（{}ms 无增量），置取消信号 runId={}",
                            spec.config().stallTimeoutMillis(), spec.runId());
                    signal.cancel();
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        try {
            streamCall.run();
        } finally {
            collector.markTerminated();
        }
    }

    // =====================================================================
    // 钩子安全包装：三条框架强制纪律的落点
    // =====================================================================

    private AgentContext safeTransformContext(RunSpec spec, AgentContext context) {
        if (spec.hooks().transformContext() == null) {
            return context;
        }
        try {
            AgentContext transformed = spec.hooks().transformContext().transform(context);
            // 返回空保持现状；改动只作用副本（transformed 是新对象，原 context 不动）
            return transformed != null ? transformed : context;
        } catch (Exception e) {
            log.warn("transformContext 钩子异常，降级不生效 runId={}", spec.runId(), e);
            return context;
        }
    }

    private List<LlmMessage> safeConvertToLlm(RunSpec spec, List<AgentMessage> transcript) {
        try {
            List<LlmMessage> converted = spec.hooks().convertToLlm().convert(List.copyOf(transcript));
            return converted != null ? converted : List.of();
        } catch (Exception e) {
            // convertToLlm 必需钩子异常也降级为空投影（不拖垮推理：空上下文调用继续）
            log.warn("convertToLlm 钩子异常，降级为空投影 runId={}", spec.runId(), e);
            return List.of();
        }
    }

    private String safeResolveCredentials(RunSpec spec) {
        if (spec.hooks().resolveCredentials() == null) {
            return null;
        }
        try {
            return spec.hooks().resolveCredentials().resolve();
        } catch (Exception e) {
            log.warn("resolveCredentials 钩子异常，按无凭证处理 runId={}", spec.runId(), e);
            return null;
        }
    }

    private LoopHooks.BeforeToolCallHook.Interception safeBeforeToolCall(
            RunSpec spec, ToolInvocation invocation) {
        if (spec.hooks().beforeToolCall() == null) {
            return null;
        }
        try {
            return spec.hooks().beforeToolCall().intercept(invocation);
        } catch (Exception e) {
            log.warn("beforeToolCall 钩子异常，放行执行 runId={}", spec.runId(), e);
            return null;
        }
    }

    private ToolResultMessage safeAfterToolCall(RunSpec spec, ToolInvocation invocation,
                                                ToolResultMessage result) {
        if (spec.hooks().afterToolCall() == null || invocation == null) {
            return result;
        }
        try {
            AgentToolResult refined = spec.hooks().afterToolCall().refine(invocation, new AgentToolResult(
                    result.content(), result.isError(), result.terminate(), result.attachments()));
            if (refined == null) {
                return result;
            }
            return new ToolResultMessage(result.messageId(), result.occurredAt(), result.weight(),
                    result.sourceRef(), result.callId(), result.toolName(),
                    refined.content(), refined.isError(), refined.terminate(), refined.attachments());
        } catch (Exception e) {
            log.warn("afterToolCall 钩子异常，保持原结果 runId={}", spec.runId(), e);
            return result;
        }
    }

    private AgentContext safePrepareNextTurn(RunSpec spec, AgentContext context,
                                             RunConfig config, int completedTurns) {
        if (spec.hooks().prepareNextTurn() == null) {
            return null;
        }
        try {
            return spec.hooks().prepareNextTurn().prepareNext(context, config, completedTurns);
        } catch (Exception e) {
            log.warn("prepareNextTurn 钩子异常，保持现状 runId={}", spec.runId(), e);
            return null;
        }
    }

    private boolean safeShouldStop(RunSpec spec, int completedTurns, RunConfig config) {
        if (spec.hooks().shouldStopAfterTurn() == null) {
            return false;
        }
        try {
            return spec.hooks().shouldStopAfterTurn().shouldStop(completedTurns, config);
        } catch (Exception e) {
            log.warn("shouldStopAfterTurn 钩子异常，继续循环 runId={}", spec.runId(), e);
            return false;
        }
    }

    private void safePersist(RunSpec spec, List<AgentMessage> turnMessages) {
        if (spec.persister() == null || turnMessages.isEmpty()) {
            return;
        }
        try {
            spec.persister().persistTurn(spec.runId(), turnMessages);
        } catch (Exception e) {
            // 落库失败不拖垮推理（崩溃最多丢当前轮次是既定语义；engine 侧实现负责告警）
            log.error("轮次落库失败（不中断 Run） errorCode=engine.turn.persist_failed runId={}",
                    spec.runId(), e);
        }
    }

    private RunOutcome finish(RunSpec spec, String stopReason, List<AgentMessage> newMessages,
                              int completedTurns) {
        SpeechOutcomeJudger.SpeechOutcome speech = SpeechOutcomeJudger.judge(newMessages);
        emit(spec, LoopEvent.RunEvent.ended(spec.runId(), stopReason,
                speech.outboxIds(), speech.silent() ? speech.silentReason() : null));
        return new RunOutcome(stopReason, speech.outboxIds(), speech.fallbackText(),
                speech.silent(), List.copyOf(newMessages));
    }

    private void emit(RunSpec spec, LoopEvent event) {
        if (spec.eventListener() == null) {
            return;
        }
        try {
            spec.eventListener().onEvent(event);
        } catch (Exception e) {
            // 监听器异常不外溢（结构保证非自觉）
            log.warn("循环事件监听器异常（不外溢） runId={}", spec.runId(), e);
        }
    }

    private boolean isParsableJson(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private String declareTool(ToolDescriptor descriptor) {
        // 工具声明：name + description + schema 的紧凑 JSON（方言翻译器再转协议格式）
        return "{\"name\":\"" + descriptor.name() + "\",\"description\":\""
                + descriptor.description().replace("\"", "'").replace("\n", " ")
                + "\",\"parameters\":" + descriptor.parametersJsonSchema() + "}";
    }

    // =====================================================================
    // 流事件收集器
    // =====================================================================

    /**
     * 十一种流事件的收集侧：增量实时累积、活跃时刻记录（卡死检测输入）、
     * Completed/Failed 终止态判定。半截内容在 Failed 后可组装为带错误标记的残缺条目。
     */
    static final class StreamCollector implements StreamConsumer {

        private final Clock clock;

        private final LongSupplier idSupplier;

        private final StringBuilder textBuffer = new StringBuilder();

        private final Map<Integer, AssistantMessage.ToolCallSegment> toolCallBuffers = new LinkedHashMap<>();

        private AssistantMessage completed;

        private String failedErrorCode;

        private String failedErrorMessage;

        private boolean aborted;

        private volatile long lastActivityNanos = System.nanoTime();

        private volatile boolean terminated;

        StreamCollector(Clock clock, LongSupplier idSupplier) {
            this.clock = clock;
            this.idSupplier = idSupplier;
        }

        @Override
        public void onEvent(ModelStreamEvent event) {
            lastActivityNanos = System.nanoTime();
            if (event instanceof ModelStreamEvent.Completed completedEvent) {
                this.completed = completedEvent.message();
                this.terminated = true;
            } else if (event instanceof ModelStreamEvent.Failed failed) {
                this.failedErrorCode = failed.errorCode();
                this.failedErrorMessage = failed.errorMessage();
                this.aborted = failed.errorCode() != null && failed.errorCode().endsWith("aborted");
                this.terminated = true;
            } else if (event instanceof ModelStreamEvent.TextDelta textDelta) {
                textBuffer.append(textDelta.text());
            } else if (event instanceof ModelStreamEvent.ToolCallStart start) {
                toolCallBuffers.put(start.index(),
                        new AssistantMessage.ToolCallSegment(start.callId(), start.name(), ""));
            } else if (event instanceof ModelStreamEvent.ToolCallDelta delta) {
                AssistantMessage.ToolCallSegment existing = toolCallBuffers.get(delta.index());
                if (existing != null) {
                    toolCallBuffers.put(delta.index(), new AssistantMessage.ToolCallSegment(
                            existing.callId(), existing.name(),
                            existing.argumentsJson() + delta.argumentsFragment()));
                }
            }
            // Thinking* / Text* 边界事件与 ToolCallEnd：活跃信号已记录，无需累积
        }

        AssistantMessage takeCompleted() {
            return completed;
        }

        /**
         * Failed 后组装残缺条目：已累积的文本与工具调用分段 + 错误标记
         * （保留转录给模型看，永不投递——残缺纪律）
         */
        AssistantMessage buildDefectiveMessage() {
            boolean hasText = textBuffer.length() > 0;
            boolean hasCalls = !toolCallBuffers.isEmpty();
            if (!hasText && !hasCalls) {
                return null;
            }
            String stopReason = aborted ? AssistantMessage.STOP_REASON_ABORTED
                    : AssistantMessage.STOP_REASON_ERROR;
            return new AssistantMessage(
                    Long.toUnsignedString(idSupplier.getAsLong()), clock.instant(), 60,
                    null, textBuffer.toString(),
                    List.copyOf(toolCallBuffers.values()), stopReason,
                    AssistantMessage.TokenUsage.ZERO);
        }

        boolean aborted() {
            return aborted;
        }

        boolean terminated() {
            return terminated;
        }

        void markTerminated() {
            terminated = true;
        }

        long nanosSinceLastActivity() {
            return System.nanoTime() - lastActivityNanos;
        }
    }
}
