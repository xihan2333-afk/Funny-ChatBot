package cn.edu.scut.chatbot.engine.outbox;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.contract.in.DeliveryReportPayload;
import cn.edu.scut.chatbot.engine.EngineErrorCodes;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.engine.turn.TurnStatus;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出站闭环（spec US3、契约 redis-streams 第 2 节）：enqueueOutbound 先落库取得 outboxId
 * 再入 dispatch Effect（禁止发出即忘）；settleDelivery 回执结算并推进 Turn。
 *
 * <p>进程外可见性纪律：出流消息必能在 outbox 表找到先行落库行（时序断言）；
 * 崩溃恢复凭本表查明在途发言、杜绝对外重复。
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    /**
     * 不可重试失败分类（凭据）：这些失败回执把 Turn 置 FAILED（已发不撤回、照实结算）
     */
    private static final List<String> NON_RETRYABLE_FAILURE_KINDS = List.of(
            "forbidden", "unsupported", "group_muted", "not_in_group", "content_too_long");

    private final OutboxMapper outboxMapper;
    private final EffectsQueueMapper effectsQueueMapper;
    private final TurnMapper turnMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final cn.edu.scut.chatbot.engine.session.TranscriptIntakeWriter transcriptIntakeWriter;
    private final cn.edu.scut.chatbot.engine.intake.TriggerEventMapper triggerEventMapper;

    public OutboxWriter(OutboxMapper outboxMapper,
                        EffectsQueueMapper effectsQueueMapper,
                        TurnMapper turnMapper,
                        IdGenerator idGenerator,
                        Clock clock,
                        cn.edu.scut.chatbot.engine.session.TranscriptIntakeWriter transcriptIntakeWriter,
                        cn.edu.scut.chatbot.engine.intake.TriggerEventMapper triggerEventMapper) {
        this.outboxMapper = outboxMapper;
        this.effectsQueueMapper = effectsQueueMapper;
        this.turnMapper = turnMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.transcriptIntakeWriter = transcriptIntakeWriter;
        this.triggerEventMapper = triggerEventMapper;
    }

    /**
     * 引擎事件发布器（回执结算 DeliverySettled 与结算段 Turn 推进发射——观测桥的
     * delivery.settle Span 数据源）。setter 注入可选装配：未装配时零开销，测试构造不受扰。
     */
    private cn.edu.scut.chatbot.engine.events.EngineEventPublisher engineEvents;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEngineEventPublisher(cn.edu.scut.chatbot.engine.events.EngineEventPublisher publisher) {
        this.engineEvents = publisher;
    }

    private void emitDeliverySettled(OutboxDO outbox, String status) {
        if (engineEvents != null) {
            engineEvents.publish(new cn.edu.scut.chatbot.engine.events.EngineEvent.DeliverySettled(
                    outbox.getPlatform(), outbox.getBotId(), outbox.getGroupId(),
                    outbox.getId(), status, traceIdOf(outbox.getTraceContext()),
                    outbox.getTraceContext()));
        }
    }

    private void emitTurnSettlement(TurnDO turn, String to, java.util.List<String> speechOutboxIds) {
        if (engineEvents != null) {
            engineEvents.publish(new cn.edu.scut.chatbot.engine.events.EngineEvent.TurnTransitioned(
                    turn.getPlatform(), turn.getBotId(), turn.getGroupId(),
                    turn.getId(), TurnStatus.SETTLING.name(), to, speechOutboxIds));
        }
    }

    /**
     * W3C traceparent（00-{traceId}-{spanId}-{flags}）→ traceId 段（回执引用原出事件）
     */
    static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return parts.length == 4 ? parts[1] : null;
    }

    /**
     * 出事件落库（先发号再落库）+ 入 dispatch Effect（事务提交后由 Effects 投递出流）。
     *
     * @return outboxId（sendMessage 工具结果的回带附件）
     */
    @Transactional
    public long enqueueOutbound(String platform, String botId, String groupId, String type,
                                String payloadJson, Long turnId, Long taskId) {
        long outboxId = idGenerator.nextId();
        OutboxDO outbox = new OutboxDO();
        outbox.setId(outboxId);
        outbox.setPlatform(platform);
        outbox.setBotId(botId);
        outbox.setGroupId(groupId);
        outbox.setType(type);
        outbox.setPayload(payloadJson);
        outbox.setTurnId(turnId);
        outbox.setTaskId(taskId);
        outbox.setTraceContext(outboundTraceparent(type, turnId));
        outbox.setStatus(OutboxDO.STATUS_PENDING);
        outboxMapper.insertOutbox(outbox);

        EffectsQueueDO dispatch = new EffectsQueueDO();
        dispatch.setPlatform(platform);
        dispatch.setBotId(botId);
        dispatch.setGroupId(groupId);
        dispatch.setKind(EffectsQueueDO.KIND_DISPATCH);
        dispatch.setPayload("{\"outboxId\":" + outboxId + "}");
        dispatch.setNextRunAt(LocalDateTime.now(clock));
        effectsQueueMapper.enqueueMerge(dispatch);
        return outboxId;
    }

    /**
     * 按字段结算（管理面手工补录回执的入口——OutboxCommands 门面用）
     */
    @Transactional
    public void settleDeliveryStatus(long outboxId, String status, String reportJson, String errorCode) {
        OutboxDO outbox = outboxMapper.selectById(outboxId);
        if (outbox == null) {
            return;
        }
        boolean firstSettlement = OutboxDO.STATUS_PENDING.equals(outbox.getStatus());
        outboxMapper.settleReport(outboxId, status, reportJson, errorCode, LocalDateTime.now(clock));
        // 手工补录也进历史（合成回执载荷——补录是回执事实的等价登记）；仅首次结算写，
        // 重复补录不双写转录
        if (firstSettlement) {
            transcriptIntakeWriter.writeDeliveryReport(
                    new DeliveryReportPayload(Long.toUnsignedString(outboxId), null,
                            OutboxDO.STATUS_FAILED.equals(status) ? "failed" : "success",
                            errorCode, null, null, clock.millis(), false),
                    outbox.getPlatform(), outbox.getBotId(), outbox.getGroupId());
            emitDeliverySettled(outbox, status);
        }
        if (outbox.getTurnId() != null) {
            advanceTurnSettlement(outbox.getTurnId());
        }
    }

    /**
     * 回执事实结算（过去式）：按 outboxId 回填 outbox 行；关联 Turn 满足结算条件时推进
     * （全部 settled → SETTLED、任一不可重试失败 → FAILED——已发不撤回）
     */
    @Transactional
    public void settleDelivery(DeliveryReportPayload report) {
        long outboxId = Long.parseLong(report.outboxId());
        OutboxDO outbox = outboxMapper.selectById(outboxId);
        if (outbox == null) {
            log.error("回执关联到不存在的 outbox 行 errorCode={} outboxId={}",
                    EngineErrorCodes.OUTBOX_REPORT_ORPHAN, outboxId);
            return;   // 数据不一致：告警留痕，不抛（登记动作自身无故障）
        }
        String status = "success".equals(report.status())
                ? OutboxDO.STATUS_SETTLED : OutboxDO.STATUS_FAILED;
        // 幂等口径：仅首次结算（原状态 PENDING）写回执条目——至少一次语义下重投不双写转录
        boolean firstSettlement = OutboxDO.STATUS_PENDING.equals(outbox.getStatus());
        outboxMapper.settleReport(outboxId, status,
                cn.edu.scut.chatbot.bootstrap.JsonMappers.valueToStringOrNull(report),
                report.failureKind(), LocalDateTime.now(clock));
        // 回执事实进历史：下一次推理才可见（不注入进行中的生成）
        if (firstSettlement) {
            transcriptIntakeWriter.writeDeliveryReport(report,
                    outbox.getPlatform(), outbox.getBotId(), outbox.getGroupId());
            emitDeliverySettled(outbox, status);
        }

        if (outbox.getTurnId() == null) {
            return;   // 非_turn 关联（如任务留痕发言）——结算到行即止
        }
        advanceTurnSettlement(outbox.getTurnId());
    }

    /**
     * Turn 结算推进：进入时先查已到回执（回执早于生成结束到达的既有场景）
     */
    public void advanceTurnSettlement(long turnId) {
        TurnDO turn = turnMapper.selectById(turnId);
        if (turn == null) {
            return;
        }
        if (!TurnStatus.SETTLING.name().equals(turn.getStatus())) {
            return;   // GENERATING 中回执到达：行已回填，Turn 到 SETTLING 时再查（SETTLING 进入即查已到回执）
        }
        List<OutboxDO> outs = outboxMapper.selectByTurn(turnId);
        boolean anyNonRetryableFailed = false;
        boolean allSettled = !outs.isEmpty();
        for (OutboxDO out : outs) {
            if (OutboxDO.STATUS_PENDING.equals(out.getStatus())) {
                allSettled = false;
            }
            if (OutboxDO.STATUS_FAILED.equals(out.getStatus())
                    && out.getErrorCode() != null
                    && NON_RETRYABLE_FAILURE_KINDS.contains(out.getErrorCode())) {
                anyNonRetryableFailed = true;
            }
        }
        if (anyNonRetryableFailed) {
            int updated = turnMapper.advanceStatus(turnId, TurnStatus.SETTLING.name(),
                    TurnStatus.FAILED.name(), LocalDateTime.now(clock), null,
                    EngineErrorCodes.OUTBOX_DISPATCH_FAILED);
            if (updated == 0) {
                logNonZeroAdvance(turnId);
            } else {
                emitTurnSettlement(turn, TurnStatus.FAILED.name(),
                        outs.stream().map(row -> String.valueOf(row.getId())).toList());
            }
        } else if (allSettled) {
            int updated = turnMapper.advanceStatus(turnId, TurnStatus.SETTLING.name(),
                    TurnStatus.SETTLED.name(), LocalDateTime.now(clock), null, null);
            if (updated == 0) {
                logNonZeroAdvance(turnId);
            } else {
                emitTurnSettlement(turn, TurnStatus.SETTLED.name(),
                        outs.stream().map(row -> String.valueOf(row.getId())).toList());
            }
        }
    }

    /**
     * 重驱前按 turn_id 查在途出事件：非空则跳过重新生成直接进入结算段
     * （崩溃恢复不重复发言——Outbox 查重纪律）
     */
    public boolean hasInFlightOutbound(long turnId) {
        return !outboxMapper.selectPendingByTurn(turnId).isEmpty();
    }

    private void logNonZeroAdvance(long turnId) {
        log.warn("Turn 推进影响行数为 0（并发推进或状态已变） turnId={}", turnId);
    }

    /**
     * 出向 traceparent 取值链（T015 出事件跨进程接续）：优先取本 Turn 消化的触发器
     * trace_context（与接入层入事件同 trace——无 javaagent 的部署与 IT 也全程一条 trace）；
     * Turn 无关联（任务留痕发言等）回退当前线程环境上下文（javaagent 增强路径）；
     * 两者皆无返回 null（无追踪环境不发假值）。
     */
    private String outboundTraceparent(String type, Long turnId) {
        if (turnId != null) {
            String fromTrigger = turnTraceContext(turnId);
            if (fromTrigger != null) {
                return fromTrigger;
            }
        }
        return currentTraceparent("outbox:" + type);
    }

    /**
     * Turn → 首个触发器的 trace_context（trigger_ids JSON 数组首个可取值项）
     */
    private String turnTraceContext(long turnId) {
        try {
            TurnDO turn = turnMapper.selectById(turnId);
            if (turn == null || turn.getTriggerIds() == null || turn.getTriggerIds().isBlank()) {
                return null;
            }
            java.util.List<Long> ids = cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readValue(
                    turn.getTriggerIds(),
                    cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().getTypeFactory()
                            .constructCollectionType(java.util.List.class, Long.class));
            for (Long triggerId : ids) {
                var trigger = triggerEventMapper.selectById(triggerId);
                if (trigger != null && trigger.getTraceContext() != null) {
                    return trigger.getTraceContext();
                }
            }
        } catch (Exception e) {
            log.warn("Turn 触发器 trace_context 取数失败（回退环境上下文） turnId={}", turnId, e);
        }
        return null;
    }

    /**
     * 当前 Span 的 traceparent（无 SDK 时 invalid SpanContext 返回 null——无追踪环境不发假值）
     */
    private String currentTraceparent(String spanName) {
        try {
            Tracer tracer = GlobalOpenTelemetry.get().getTracer("chatbot-agent-core");
            Span span = tracer.spanBuilder(spanName).startSpan();
            try {
                io.opentelemetry.api.trace.SpanContext context = span.getSpanContext();
                if (!context.isValid()) {
                    return null;
                }
                return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-01";
            } finally {
                span.end();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
