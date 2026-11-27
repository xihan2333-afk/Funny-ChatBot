package cn.edu.scut.chatbot.observability.bridge;

import cn.edu.scut.chatbot.engine.events.EngineEvent;
import cn.edu.scut.chatbot.engine.events.EngineEventListener;
import cn.edu.scut.chatbot.loop.events.LoopEvent;
import cn.edu.scut.chatbot.loop.events.LoopEventListener;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 事件桥翻译表（observability-bridge 契约第 3 节、T014）：引擎事件六组 + 循环事件四组
 * 逐行映射为业务 Span——intake / gate.l0 / gate.l1 / turn / task / run / turn#N /
 * llm.call / tool.{name} / effect.{kind} / delivery.settle。
 *
 * <p>属性两级命名纪律（评估与报表只依赖 chatbot.*，gen_ai.* 仅生态互通挂 llm.call）；
 * 公共属性（chatbot.group.platform / bot_id / group_id，run 相关加 turn_id / task_id /
 * run_id）取自事件携带的上下文，不依赖 ThreadLocal——跨线程父子经显式 SpanContext 传递：
 * 引擎事件流与循环事件流各在独立队列线程上消费，父子关系靠「同一 trace 的 SpanContext
 * 显式挂接」而非调用栈。
 *
 * <p>长生命周期 Span（turn 开于 DecisionMade(accept)、task 开于创建迁移、run 开于
 * RunEvent START）登记进 {@link SpanClosureRegistry}，结束事件到达正常 close、超时由注册表
 * 强制闭合打 abnormal_end——结束事件在另一时刻另一线程到达，这是跨时刻 Span 的唯一
 * 正确解法（research R4）。
 */
public class EventBridgeSpanTranslator implements EngineEventListener, LoopEventListener {

    private static final Logger log = LoggerFactory.getLogger(EventBridgeSpanTranslator.class);

    private static final String TRACER_NAME = "chatbot-event-bridge";

    // ===== chatbot.* 属性键（契约第 3 节表格逐项；报表与评估只依赖这些键） =====
    static final String A_PLATFORM = "chatbot.group.platform";
    static final String A_BOT_ID = "chatbot.group.bot_id";
    static final String A_GROUP_ID = "chatbot.group.group_id";
    static final String A_TURN_ID = "chatbot.turn_id";
    static final String A_TASK_ID = "chatbot.task_id";
    static final String A_RUN_ID = "chatbot.run_id";
    static final String A_DEDUP_KEY = "chatbot.dedup_key";
    static final String A_EVENT_TYPE = "chatbot.event_type";
    static final String A_TRIGGER_ID = "chatbot.trigger_id";
    static final String A_DECISION = "chatbot.decision";
    static final String A_REASON = "chatbot.reason";
    static final String A_LAYER = "chatbot.layer";
    static final String A_STATUS = "chatbot.status";
    static final String A_SPEECH_COUNT = "chatbot.speech_count";
    static final String A_SPEECH_OUTBOX_IDS = "chatbot.speech_outbox_ids";
    static final String A_MANDATE = "chatbot.mandate";
    static final String A_FORM = "chatbot.form";
    static final String A_STOP_REASON = "chatbot.stop_reason";
    static final String A_SILENT_REASON = "chatbot.silent_reason";
    static final String A_TURN_INDEX = "chatbot.turn_index";
    static final String A_REPLAY = "chatbot.replay";
    static final String A_SLOT = "chatbot.slot";
    static final String A_TTFT_MS = "chatbot.ttft_ms";
    static final String A_ERROR_CODE = "chatbot.error_code";
    static final String A_MESSAGE_ID = "chatbot.message_id";
    static final String A_PARAM_DIGEST = "chatbot.param_digest";
    static final String A_IS_ERROR = "chatbot.is_error";
    static final String A_DURATION_MS = "chatbot.duration_ms";
    static final String A_OUTBOX_ID = "chatbot.outbox_id";
    static final String A_OUTCOME = "chatbot.outcome";
    static final String A_RETRY_COUNT = "chatbot.retry_count";
    static final String A_SETTLE_STATUS = "chatbot.settle_status";
    static final String A_TRACE_REF = "chatbot.trace_ref";
    static final String A_VERSION_PROMPT_FRAGMENT = "chatbot.version.promptFragment";
    static final String A_VERSION_PERSONA = "chatbot.version.persona";
    static final String A_VERSION_SLOT_BINDING = "chatbot.version.slotBinding";

    // ===== gen_ai.* 生态兼容层（仅挂 llm.call；属性名演进不构成破坏性变更） =====
    static final String G_OPERATION_NAME = "gen_ai.operation.name";
    static final String G_REQUEST_MODEL = "gen_ai.request.model";
    static final String G_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    static final String G_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    static final String G_CACHED_TOKENS = "gen_ai.usage.cached_tokens";

    /**
     * Turn 终态（闭合注册表正常 close 的迁移目标）
     */
    private static final Set<String> TURN_TERMINAL = Set.of("SETTLED", "SILENT", "FAILED", "DROPPED");

    /**
     * Task 终态
     */
    private static final Set<String> TASK_TERMINAL = Set.of("DONE", "FAILED", "CANCELLED");

    /**
     * Run 兜底超时（RunEvent.end 丢失时注册表强制闭合——正常秒级结束，30 分钟极宽）
     */
    private static final java.time.Duration RUN_CLOSURE_FALLBACK = java.time.Duration.ofMinutes(30);

    private final SpanClosureRegistry closureRegistry;

    /**
     * 工具 Span 安全标记协同器（US10 T089）：tool Span 闭合前拉取拦截三属性挂载
     */
    private cn.edu.scut.chatbot.security.audit.SpanSecurityMarker spanSecurityMarker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSpanSecurityMarker(cn.edu.scut.chatbot.security.audit.SpanSecurityMarker marker) {
        this.spanSecurityMarker = marker;
    }

    /**
     * Tracer 来源：生产形态永远经全局入口（GlobalOpenTelemetry——javaagent 与自建 SDK
     * 双层拿到的是同一实例）；测试注入内存 SDK（观测代码零感知是 R1 的结构面）
     */
    private final Supplier<Tracer> tracerSource;

    /**
     * turnId → 在途 turn Span（桥侧索引；兜底闭合与结束事件都经注册表）
     */
    private final Map<Long, Span> openTurns = new ConcurrentHashMap<>();

    /**
     * turnId → 远端父 SpanContext（DecisionMade(accept) 时刻记下）：run Span 开启时若
     * turn Span 已被消费建好则挂其下；尚未消费到（事件流时序缝隙）则直接挂远端父——
     * 两条路径 traceId 与接入层一致（跨进程接续的连续性保证，比父子层级更优先）
     */
    private final Map<Long, SpanContext> turnRemoteParents = new ConcurrentHashMap<>();

    /**
     * taskId → 在途 task Span
     */
    private final Map<Long, Span> openTasks = new ConcurrentHashMap<>();

    /**
     * runId → Run 状态（run Span、群身份、当前轮次 Span、在途工具 Span）
     */
    private final Map<Long, RunState> runs = new ConcurrentHashMap<>();

    public EventBridgeSpanTranslator(SpanClosureRegistry closureRegistry) {
        this(closureRegistry, () -> GlobalOpenTelemetry.get().getTracer(TRACER_NAME));
    }

    /**
     * 测试注入形态（包可见）：翻译表断言经内存 SpanExporter 收集，不碰全局注册
     */
    EventBridgeSpanTranslator(SpanClosureRegistry closureRegistry, Supplier<Tracer> tracerSource) {
        this.closureRegistry = closureRegistry;
        this.tracerSource = tracerSource;
    }

    /**
     * Run 级翻译状态：run Span 长生命周期（START 开、END 闭）；currentTurnSpan 随
     * TurnEvent START 切换——llm.call 与 tool Span 挂当前轮次下（轮次是父子关系的自然层）
     */
    private static final class RunState {
        final Span runSpan;
        final LoopEvent.RunIdentity identity;
        volatile Span currentTurnSpan;
        final Map<String, Span> openTools = new ConcurrentHashMap<>();

        RunState(Span runSpan, LoopEvent.RunIdentity identity) {
            this.runSpan = runSpan;
            this.identity = identity;
        }
    }

    // =====================================================================
    // 引擎事件翻译（六组）
    // =====================================================================

    @Override
    public void onEvent(EngineEvent event) {
        try {
            switch (event) {
                case EngineEvent.IntakeRegistered e -> onIntake(e);
                case EngineEvent.DecisionMade e -> onDecision(e);
                case EngineEvent.TurnTransitioned e -> onTurnTransition(e);
                case EngineEvent.TaskTransitioned e -> onTaskTransition(e);
                case EngineEvent.EffectExecuted e -> onEffect(e);
                case EngineEvent.DeliverySettled e -> onDeliverySettled(e);
            }
        } catch (Exception translateFailure) {
            // 翻译失败不外溢（监听器纪律）；观测数据缺一段不拖垮任何事件流
            log.warn("引擎事件翻译异常（不外溢） event={}", event.getClass().getSimpleName(), translateFailure);
        }
    }

    private void onIntake(EngineEvent.IntakeRegistered e) {
        Span span = shortSpan("intake", remoteParent(e.traceContext()));
        commonGroup(span, e.platform(), e.botId(), e.groupId());
        span.setAttribute(A_DEDUP_KEY, e.dedupKey());
        span.setAttribute(A_EVENT_TYPE, e.eventType());
        span.end();
    }

    private void onDecision(EngineEvent.DecisionMade e) {
        SpanContext remote = TracePropagation.extract(e.traceContext());
        Context parent = remote != null
                ? Context.root().with(Span.wrap(remote))
                : Context.root();
        Span span = shortSpan("L0".equals(e.layer()) ? "gate.l0" : "gate.l1", parent);
        commonGroup(span, e.platform(), e.botId(), e.groupId());
        span.setAttribute(A_TRIGGER_ID, e.triggerId());
        span.setAttribute(A_DECISION, e.decision());
        span.setAttribute(A_REASON, e.reason());
        span.setAttribute(A_LAYER, e.layer());
        if (e.turnId() != null) {
            span.setAttribute(A_TURN_ID, e.turnId());
        }
        span.end();
        if ("accept".equals(e.decision()) && e.turnId() != null) {
            openTurnSpan(e.turnId(), remote, e.platform(), e.botId(), e.groupId());
        }
    }

    /**
     * turn Span 开于 DecisionMade(accept)（契约明文）——同远端父上下文，注册表登记
     * 兜底超时；后续 run 及其子 Span 经显式 SpanContext 挂其下
     */
    private void openTurnSpan(long turnId, SpanContext remote, String platform, String botId, String groupId) {
        if (remote != null) {
            turnRemoteParents.put(turnId, remote);
        }
        Span turn = tracer().spanBuilder("turn")
                .setParent(remote != null ? Context.root().with(Span.wrap(remote)) : Context.root())
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        commonGroup(turn, platform, botId, groupId);
        turn.setAttribute(A_TURN_ID, turnId);
        turn.setAttribute(A_STATUS, "ACCEPTED");
        openTurns.put(turnId, turn);
        closureRegistry.register("turn:" + turnId, turn, closureRegistry.turnTimeout());
    }

    private void onTurnTransition(EngineEvent.TurnTransitioned e) {
        if (TURN_TERMINAL.contains(e.toStatus())) {
            turnRemoteParents.remove(e.turnId());
        }
        Span turn = openTurns.get(e.turnId());
        if (turn == null) {
            // L1 accept 现行不建 Turn、或事件乱序早于 DecisionMade——非终态忽略即可
            return;
        }
        turn.setAttribute(A_STATUS, e.toStatus());
        if (TURN_TERMINAL.contains(e.toStatus())) {
            turn.setAttribute(A_SPEECH_COUNT, e.speechOutboxIds().size());
            turn.setAttribute(A_SPEECH_OUTBOX_IDS, String.join(",", e.speechOutboxIds()));
            openTurns.remove(e.turnId());
            closureRegistry.close("turn:" + e.turnId());
        }
    }

    private void onTaskTransition(EngineEvent.TaskTransitioned e) {
        if (e.fromStatus() == null) {
            // 创建迁移：开 task Span（内部触发的主动行为——无远端父，独立根 trace）
            Span task = tracer().spanBuilder("task")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan();
            commonGroup(task, e.platform(), e.botId(), e.groupId());
            task.setAttribute(A_TASK_ID, e.taskId());
            task.setAttribute(A_MANDATE, e.mandateKind());
            task.setAttribute(A_STATUS, e.toStatus());
            openTasks.put(e.taskId(), task);
            closureRegistry.register("task:" + e.taskId(), task, closureRegistry.taskTimeout());
            return;
        }
        Span task = openTasks.get(e.taskId());
        if (task == null) {
            return;
        }
        task.setAttribute(A_STATUS, e.toStatus());
        if (TASK_TERMINAL.contains(e.toStatus())) {
            openTasks.remove(e.taskId());
            closureRegistry.close("task:" + e.taskId());
        }
    }

    private void onEffect(EngineEvent.EffectExecuted e) {
        Span span = shortSpan("effect." + e.kind(), Context.root());
        commonGroup(span, e.platform(), e.botId(), e.groupId());
        span.setAttribute(A_OUTCOME, e.outcome());
        span.setAttribute(A_RETRY_COUNT, e.retryCount());
        span.end();
    }

    private void onDeliverySettled(EngineEvent.DeliverySettled e) {
        // 结算 Span 挂回原出事件的 trace（回执引用——链路页从回执能跳回投递现场）
        Span span = shortSpan("delivery.settle", remoteParent(e.traceContext()));
        commonGroup(span, e.platform(), e.botId(), e.groupId());
        span.setAttribute(A_OUTBOX_ID, e.outboxId());
        span.setAttribute(A_SETTLE_STATUS, e.settleStatus());
        if (e.originalTraceId() != null) {
            span.setAttribute(A_TRACE_REF, e.originalTraceId());
        }
        span.end();
    }

    // =====================================================================
    // 循环事件翻译（四组）
    // =====================================================================

    @Override
    public void onEvent(LoopEvent event) {
        // MDC 协同（T021）：run 级事件处理期间，桥消费线程的日志带 run 链路标识；
        // START 时状态尚未建（noop 形态），建毕后续事件即生效
        RunState preexisting = runs.get(event.runId());
        try (MdcTraceScope ignored = preexisting != null
                ? MdcTraceScope.activate(preexisting.runSpan.getSpanContext(), preexisting.identity)
                : MdcTraceScope.noop()) {
            try {
                switch (event) {
                    case LoopEvent.RunEvent run -> onRun(run);
                    case LoopEvent.TurnEvent turn -> onTurnEvent(turn);
                    case LoopEvent.MessageEvent message -> onMessage(message);
                    case LoopEvent.ToolEvent tool -> onTool(tool);
                }
            } catch (Exception translateFailure) {
                log.warn("循环事件翻译异常（不外溢） event={}", event.getClass().getSimpleName(), translateFailure);
            }
        }
    }

    private void onRun(LoopEvent.RunEvent event) {
        if (event.phase() != LoopEvent.Phase.START) {
            RunState state = runs.remove(event.runId());
            if (state == null) {
                return;
            }
            state.runSpan.setAttribute(A_STOP_REASON, event.stopReason() == null
                    ? "unknown" : event.stopReason());
            if (event.silentReason() != null) {
                state.runSpan.setAttribute(A_SILENT_REASON, event.silentReason());
            }
            state.runSpan.setAttribute(A_SPEECH_COUNT, event.speechOutboxIds().size());
            // 经注册表闭合（abnormal_end=false 显式留痕；超时兜底已先闭时此处 no-op）
            closureRegistry.close("run:" + event.runId());
            return;
        }

        LoopEvent.RunIdentity identity = event.identity();
        if (identity == null) {
            // 无群身份的 Run（测试裸跑形态）：开根 Span 但不参与父子
            identity = new LoopEvent.RunIdentity(null, null, null, null, null);
        }
        // run Span 的父：turn Span（Turn 形态）或 task Span（轻任务/Worker/交付形态）；
        // turn Span 未消费到时回退该 turn 的远端父（同 traceId 接续优先于父子层级）；
        // 都缺席开根——跨线程经显式 SpanContext 挂接，不依赖 ThreadLocal
        Span parentSpan = null;
        SpanContext remoteFallback = null;
        if (identity.turnId() != null) {
            parentSpan = openTurns.get(identity.turnId());
            remoteFallback = turnRemoteParents.get(identity.turnId());
        }
        if (parentSpan == null && identity.taskId() != null) {
            parentSpan = openTasks.get(identity.taskId());
        }
        Context parent = parentSpan != null
                ? Context.root().with(parentSpan)
                : remoteFallback != null
                ? Context.root().with(Span.wrap(remoteFallback))
                : Context.root();
        Span run = tracer().spanBuilder("run")
                .setParent(parent)
                .startSpan();
        commonGroup(run, identity.platform(), identity.botId(), identity.groupId());
        run.setAttribute(A_RUN_ID, event.runId());
        if (event.form() != null) {
            run.setAttribute(A_FORM, event.form().name());
        }
        if (identity.turnId() != null) {
            run.setAttribute(A_TURN_ID, identity.turnId());
        }
        if (identity.taskId() != null) {
            run.setAttribute(A_TASK_ID, identity.taskId());
        }
        // 版本三键挂 run Span（重放按版本分组聚合的口径——契约第 3 节）
        if (event.versionKeys() != null) {
            run.setAttribute(A_VERSION_PROMPT_FRAGMENT, event.versionKeys().promptFragment());
            run.setAttribute(A_VERSION_PERSONA, event.versionKeys().persona());
            run.setAttribute(A_VERSION_SLOT_BINDING, event.versionKeys().slotBinding());
        }
        // dry-run 重放标记（T055 记账贯通）：报表按 chatbot.replay 区分线上与重放
        if (event.replay()) {
            run.setAttribute(A_REPLAY, true);
        }
        RunState state = new RunState(run, identity);
        runs.put(event.runId(), state);
        closureRegistry.register("run:" + event.runId(), run, RUN_CLOSURE_FALLBACK);
    }

    private void onTurnEvent(LoopEvent.TurnEvent event) {
        RunState state = runs.get(event.runId());
        if (state == null) {
            return;
        }
        if (event.phase() == LoopEvent.Phase.START) {
            Span turnSpan = tracer().spanBuilder("turn#" + event.turnIndex())
                    .setParent(Context.root().with(parentOf(state)))
                    .startSpan();
            commonGroup(turnSpan, state.identity.platform(), state.identity.botId(), state.identity.groupId());
            turnSpan.setAttribute(A_RUN_ID, event.runId());
            turnSpan.setAttribute(A_TURN_INDEX, event.turnIndex());
            state.currentTurnSpan = turnSpan;
        } else {
            Span turnSpan = state.currentTurnSpan;
            if (turnSpan != null) {
                turnSpan.end();
                state.currentTurnSpan = null;
            }
        }
    }

    private void onMessage(LoopEvent.MessageEvent event) {
        RunState state = runs.get(event.runId());
        if (state == null || event.usage() == null) {
            return;
        }
        LoopEvent.UsageSummary usage = event.usage();
        Span span = tracer().spanBuilder("llm.call")
                .setParent(Context.root().with(parentOf(state)))
                .startSpan();
        commonGroup(span, state.identity.platform(), state.identity.botId(), state.identity.groupId());
        span.setAttribute(A_RUN_ID, event.runId());
        span.setAttribute(A_SLOT, usage.slotCode());
        span.setAttribute(A_TTFT_MS, usage.ttftMs());
        span.setAttribute(A_STOP_REASON, usage.stopReason() == null ? "unknown" : usage.stopReason());
        if (usage.errorCode() != null) {
            span.setAttribute(A_ERROR_CODE, usage.errorCode());
        }
        if (event.messageId() != null) {
            span.setAttribute(A_MESSAGE_ID, event.messageId());
        }
        // gen_ai.* 生态兼容层（仅此一组挂——契约两级命名纪律）
        span.setAttribute(G_OPERATION_NAME, "chat");
        if (usage.modelId() != null) {
            span.setAttribute(G_REQUEST_MODEL, usage.modelId());
        }
        span.setAttribute(G_INPUT_TOKENS, usage.inputTokens());
        span.setAttribute(G_OUTPUT_TOKENS, usage.outputTokens());
        span.setAttribute(G_CACHED_TOKENS, usage.cachedTokens());
        span.end();
    }

    private void onTool(LoopEvent.ToolEvent event) {
        RunState state = runs.get(event.runId());
        if (state == null) {
            return;
        }
        String spanName = "tool." + event.toolName();
        if (event.phase() == LoopEvent.Phase.START) {
            Span tool = tracer().spanBuilder(spanName)
                    .setParent(Context.root().with(parentOf(state)))
                    .startSpan();
            commonGroup(tool, state.identity.platform(), state.identity.botId(), state.identity.groupId());
            tool.setAttribute(A_RUN_ID, event.runId());
            state.openTools.put(event.callId(), tool);
            return;
        }
        cn.edu.scut.chatbot.security.audit.SpanSecurityMarker.SecurityMark mark = spanSecurityMarker != null
                ? spanSecurityMarker.consumePending(
                state.identity.platform(), state.identity.botId(), state.identity.groupId(), event.toolName())
                : null;
        Span tool = state.openTools.remove(event.callId());
        if (tool == null) {
            return;   // START 事件被丢弃（队列满）：半截记录无审计价值，跳过（pending 标记已清理防泄漏）
        }
        tool.setAttribute(A_IS_ERROR, event.isError());
        tool.setAttribute(A_DURATION_MS, event.durationMs());
        if (event.paramDigest() != null) {
            tool.setAttribute(A_PARAM_DIGEST, event.paramDigest());
        }
        if (event.outboxId() != null) {
            tool.setAttribute(A_OUTBOX_ID, event.outboxId());
        }
        if (mark != null) {
            cn.edu.scut.chatbot.security.audit.SpanSecurityMarker.mark(tool, mark.rule(), mark.channel());
        }
        tool.end();
    }

    /**
     * 当前轮次 Span 在场挂轮次下，否则挂 run（轮次事件丢失时不丢调用记录）
     */
    private static Span parentOf(RunState state) {
        Span current = state.currentTurnSpan;
        return current != null ? current : state.runSpan;
    }

    // =====================================================================
    // 公共工具
    // =====================================================================

    /**
     * W3C traceparent → 远端父上下文（非法容忍失败开根——接续纪律，T015）
     */
    private static Context remoteParent(String traceContext) {
        SpanContext remote = TracePropagation.extract(traceContext);
        return remote != null ? Context.root().with(Span.wrap(remote)) : Context.root();
    }

    /**
     * 短生命周期 Span 工厂（开即用即闭——本翻译器的绝大多数 Span 形态）
     */
    private Span shortSpan(String name, Context parent) {
        return tracer().spanBuilder(name).setParent(parent).startSpan();
    }

    /**
     * 公共群属性（翻译表「公共属性取自事件上下文」）——空维度跳过不发假值
     */
    private static void commonGroup(Span span, String platform, String botId, String groupId) {
        if (platform != null) {
            span.setAttribute(A_PLATFORM, platform);
        }
        if (botId != null) {
            span.setAttribute(A_BOT_ID, botId);
        }
        if (groupId != null) {
            span.setAttribute(A_GROUP_ID, groupId);
        }
    }

    private Tracer tracer() {
        return tracerSource.get();
    }

    int openRunCount() {
        return runs.size();
    }
}
