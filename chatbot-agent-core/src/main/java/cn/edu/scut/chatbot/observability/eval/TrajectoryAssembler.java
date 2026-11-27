package cn.edu.scut.chatbot.observability.eval;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.TranscriptEntryDO;
import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import cn.edu.scut.chatbot.exception.RetryableException;
import cn.edu.scut.chatbot.infrastructure.es.SpanView;
import cn.edu.scut.chatbot.infrastructure.es.TraceQueryService;
import cn.edu.scut.chatbot.observability.bridge.TracePropagation;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 轨迹组装（eval-and-flywheel 契约第 1 节、T043）：线上 Turn → 叙事化执行过程
 * 描述——决策留档（trigger_event）+ MySQL 转录原文（transcript_entry 时间窗投影）
 * + ES Span 树摘要（TraceQueryService）三段拼装，供 eval_judge 小模型阅读判分。
 *
 * <p>降级纪律：ES 不可达（observe.query_unavailable 可重试错误）不阻断组装——
 * 链路段落标注不可得继续拼（Agent 判分降级跑转录与决策，评估是后台低频任务，
 * 观测面故障不允许拖垮它）。traceId 从触发器 traceContext（W3C traceparent）
 * 解析——turn 无触发器（内部触发场景）时链路段落整体缺省。
 */
@Component
public class TrajectoryAssembler {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryAssembler.class);

    /**
     * 转录时间窗前量（分钟）：Turn 接受前的近期上下文（前情对话）
     */
    static final int TRANSCRIPT_LOOKBACK_MIN = 30;

    /**
     * 转录行数上限（输入有界——与沉淀抽取同口径）
     */
    static final int MAX_TRANSCRIPT_LINES = 200;

    /**
     * 链路段落单行属性摘要长度上限
     */
    private static final int SPAN_LINE_LIMIT = 500;

    private static final DateTimeFormatter LINE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TurnMapper turnMapper;
    private final TriggerEventMapper triggerEventMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final TraceQueryService traceQueryService;

    public TrajectoryAssembler(TurnMapper turnMapper,
                               TriggerEventMapper triggerEventMapper,
                               AgentSessionMapper agentSessionMapper,
                               TraceQueryService traceQueryService) {
        this.turnMapper = turnMapper;
        this.triggerEventMapper = triggerEventMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.traceQueryService = traceQueryService;
    }

    /**
     * 组装结果：traceId（eval_result.trace_id 落库来源）、叙事化执行过程、代码判据
     * 事实（发言条数 / 输入输出摘要——CodeRuleEvaluator 与 Agent 判分提示的取材）
     */
    public record TrajectoryView(long turnId,
                                 String traceId,
                                 String narrative,
                                 int speakCount,
                                 String inputDigest,
                                 String outputDigest) {
    }

    /**
     * 组装一个 Turn 的执行过程描述（Turn 不存在抛 IllegalArgumentException）
     */
    public TrajectoryView assembleTurn(long turnId) {
        TurnDO turn = turnMapper.selectById(turnId);
        if (turn == null) {
            throw new IllegalArgumentException("Turn 不存在：" + turnId);
        }
        List<TriggerEventDO> triggers = triggerEventMapper.selectByTurn(turnId);
        String traceId = resolveTraceId(triggers);
        String inputDigest = buildInputDigest(triggers);

        StringBuilder narrative = new StringBuilder();
        appendTurnSection(narrative, turn);
        appendDecisionSection(narrative, triggers);
        TranscriptScan scan = scanTranscript(turn);
        narrative.append(scan.sectionText());
        appendTraceSection(narrative, traceId);
        return new TrajectoryView(turnId, traceId, narrative.toString(),
                scan.speakCount(), inputDigest, scan.outputDigest());
    }

    /**
     * 触发输入摘要：触发器 digest 依序拼接（截 1024 字符——与 eval_case.input_digest 列宽一致）
     */
    private String buildInputDigest(List<TriggerEventDO> triggers) {
        if (triggers.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (TriggerEventDO trigger : triggers) {
            if (trigger.getDigest() == null || trigger.getDigest().isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" / ");
            }
            builder.append(trigger.getDigest());
            if (builder.length() >= 1024) {
                break;
            }
        }
        String digest = builder.toString();
        return digest.isBlank() ? null : digest.substring(0, Math.min(digest.length(), 1024));
    }

    private void appendTurnSection(StringBuilder builder, TurnDO turn) {
        builder.append("【Turn 概况】\n");
        builder.append("状态=").append(turn.getStatus());
        if (turn.getAcceptedAt() != null) {
            builder.append("，接受时间=").append(LINE_TIME.format(turn.getAcceptedAt()));
        }
        if (turn.getFinishedAt() != null && turn.getAcceptedAt() != null) {
            long millis = java.time.Duration.between(turn.getAcceptedAt(), turn.getFinishedAt()).toMillis();
            builder.append("，端到端时长=").append(millis).append("ms");
        }
        if (turn.getOutboxIds() != null) {
            builder.append("，出事件=").append(turn.getOutboxIds());
        }
        if (turn.getSilentReason() != null) {
            builder.append("，沉默原因=").append(turn.getSilentReason());
        }
        if (turn.getErrorCode() != null) {
            builder.append("，错误码=").append(turn.getErrorCode());
        }
        builder.append('\n');
    }

    private void appendDecisionSection(StringBuilder builder, List<TriggerEventDO> triggers) {
        builder.append("【决策留档】\n");
        if (triggers.isEmpty()) {
            builder.append("（无触发器记录——内部触发或留档缺失）\n");
            return;
        }
        for (TriggerEventDO trigger : triggers) {
            builder.append("- ").append(trigger.getEventType())
                    .append("（").append(trigger.getDigest() == null ? "" : trigger.getDigest()).append("）")
                    .append(" → ").append(trigger.getDecision());
            if (trigger.getDecisionLayer() != null) {
                builder.append("/").append(trigger.getDecisionLayer());
            }
            if (trigger.getDecisionReason() != null) {
                builder.append("：").append(trigger.getDecisionReason());
            }
            builder.append('\n');
        }
    }

    /**
     * 转录扫描结果：【转录原文】段落文本 + 代码判据事实（Turn 期间 assistant 发言
     * 条数与输出摘要）
     */
    private record TranscriptScan(String sectionText, int speakCount, String outputDigest) {
    }

    private TranscriptScan scanTranscript(TurnDO turn) {
        StringBuilder builder = new StringBuilder("【转录原文】\n");
        AgentSessionDO session = agentSessionMapper.selectByGroup(
                turn.getPlatform(), turn.getBotId(), turn.getGroupId());
        if (session == null) {
            builder.append("（会话不存在）\n");
            return new TranscriptScan(builder.toString(), 0, null);
        }
        List<TranscriptEntryDO> entries = agentSessionMapper.selectOrdered(session.getId());
        LocalDateTime windowFrom = turn.getAcceptedAt() != null
                ? turn.getAcceptedAt().minusMinutes(TRANSCRIPT_LOOKBACK_MIN) : null;
        LocalDateTime windowTo = turn.getFinishedAt() != null ? turn.getFinishedAt() : turn.getAcceptedAt();
        // 发言条数只数 Turn 期间（接受时间起）的 assistant 条目——lookback 前情不计入
        LocalDateTime speakFrom = turn.getAcceptedAt() != null
                ? turn.getAcceptedAt().minusMinutes(1) : windowFrom;
        int lines = 0;
        int speakCount = 0;
        StringBuilder output = new StringBuilder();
        for (TranscriptEntryDO entry : entries) {
            if (windowFrom != null && entry.getOccurredAt() != null
                    && entry.getOccurredAt().isBefore(windowFrom)) {
                continue;
            }
            if (windowTo != null && entry.getOccurredAt() != null
                    && entry.getOccurredAt().isAfter(windowTo.plusMinutes(1))) {
                continue;
            }
            String line = renderEntry(entry);
            if (line == null) {
                continue;
            }
            if (lines >= MAX_TRANSCRIPT_LINES) {
                builder.append("（转录超长截断，仅保留前 ").append(MAX_TRANSCRIPT_LINES).append(" 行）\n");
                break;
            }
            builder.append(line).append('\n');
            lines++;
            if ("assistant".equals(entry.getKind()) && speakFrom != null
                    && entry.getOccurredAt() != null && !entry.getOccurredAt().isBefore(speakFrom)) {
                speakCount++;
                if (output.length() > 0) {
                    output.append(" / ");
                }
                output.append(line);
            }
        }
        if (lines == 0) {
            builder.append("（时间窗内无转录条目）\n");
        }
        String digest = output.toString();
        digest = digest.isBlank() ? null : digest.substring(0, Math.min(digest.length(), 1024));
        return new TranscriptScan(builder.toString(), speakCount, digest);
    }

    private void appendTraceSection(StringBuilder builder, String traceId) {
        builder.append("【链路（ES Span 树）】\n");
        if (traceId == null) {
            builder.append("（无 traceId——触发器未携带跨进程 traceContext）\n");
            return;
        }
        List<SpanView> spans;
        try {
            spans = traceQueryService.getTrace(traceId);
        } catch (RetryableException e) {
            // 降级纪律：ES 不可达不阻断组装（类注释）
            log.warn("轨迹组装链路段落降级（traceId={}，errorCode={}）", traceId, e.getErrorCode());
            builder.append("（链路查询不可达：").append(e.getErrorCode()).append("）\n");
            return;
        }
        if (spans.isEmpty()) {
            builder.append("（该 traceId 在保留期内无 Span 文档）\n");
            return;
        }
        for (SpanView span : spans) {
            String line = renderSpan(span);
            if (line.length() > SPAN_LINE_LIMIT) {
                line = line.substring(0, SPAN_LINE_LIMIT) + "…";
            }
            builder.append(line).append('\n');
        }
    }

    /**
     * 触发器 traceContext → traceId（W3C traceparent 解析；任一合法即取）
     */
    private String resolveTraceId(List<TriggerEventDO> triggers) {
        for (TriggerEventDO trigger : triggers) {
            SpanContext context = TracePropagation.extract(trigger.getTraceContext());
            if (context != null && context.isValid()) {
                return context.getTraceId();
            }
        }
        return null;
    }

    /**
     * 转录条目投影行：user 译发言人 + 文本段、assistant 译正文与工具调用计数、
     * toolResult 译工具名与成败、summary 译摘要——坏条目跳过不杀批
     * （与沉淀抽取的投影口径一致）
     */
    private String renderEntry(TranscriptEntryDO entry) {
        try {
            JsonNode payload = JsonMappers.shared().readTree(entry.getPayload());
            String time = entry.getOccurredAt() == null ? "" : LINE_TIME.format(entry.getOccurredAt()) + " ";
            switch (entry.getKind()) {
                case "user": {
                    String sender = payload.path("senderUserId").asText("匿名");
                    StringBuilder text = new StringBuilder();
                    for (JsonNode part : payload.path("parts")) {
                        String type = part.path("part").asText("");
                        if ("text".equals(type)) {
                            text.append(part.path("text").asText(""));
                        } else if ("at".equals(type)) {
                            text.append("@").append(part.path("targetUserId").asText(""));
                        } else {
                            text.append("[非文本]");
                        }
                    }
                    return "[" + time + "]" + sender + ": "
                            + (text.isEmpty() ? "[非文本消息]" : text);
                }
                case "assistant": {
                    String text = payload.path("textContent").asText("");
                    int toolCalls = payload.path("toolCalls").isArray() ? payload.path("toolCalls").size() : 0;
                    return "[" + time + "]Agent: " + (text.isBlank() ? "（工具调用轮次，调用 "
                            + toolCalls + " 次）" : text);
                }
                case "toolResult": {
                    String toolName = payload.path("toolName").asText("unknown");
                    boolean isError = payload.path("error").asBoolean(false);
                    return "[" + time + "]工具 " + toolName + (isError ? " → 失败" : " → 完成");
                }
                case "summary": {
                    return "[" + time + "][摘要] " + payload.path("summaryText").asText("");
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Span 投影行：名称 + 时长 + 关键 chatbot.* 属性（turn_id / slot / decision /
     * error_code / replay / 版本三键）——报表只依赖 chatbot.* 的两级命名纪律
     */
    private String renderSpan(SpanView span) {
        StringBuilder builder = new StringBuilder("- ");
        builder.append(span.name()).append("（").append(span.durationMillis()).append("ms）");
        Map<String, Object> attributes = span.attributes();
        if (attributes == null || attributes.isEmpty()) {
            return builder.toString();
        }
        String[] keys = {"chatbot.turn_id", "chatbot.slot", "chatbot.decision",
                "chatbot.error_code", "chatbot.replay", "chatbot.abnormal_end",
                "chatbot.version.promptFragment", "chatbot.version.persona", "chatbot.version.slotBinding"};
        boolean first = true;
        for (String key : keys) {
            Object value = attributes.get(key);
            if (value != null) {
                if (first) {
                    builder.append(" {");
                    first = false;
                } else {
                    builder.append(", ");
                }
                builder.append(key).append('=').append(value);
            }
        }
        if (!first) {
            builder.append("}");
        }
        return builder.toString();
    }
}
