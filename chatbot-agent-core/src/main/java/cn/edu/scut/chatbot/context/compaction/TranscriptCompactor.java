package cn.edu.scut.chatbot.context.compaction;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.SummaryEntry;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩器（T094、data-model 第 13 节）：
 * 触发双通道——预防性（Run 结束查水位）+ 应急性（context_overflow 立即压缩重试，调用方驱动）；
 * 切点选择不悬挂工具对（assistant 工具调用段与配对 toolResult 必须同进同出压缩区）；
 * 社交事实五段摘要模板 + 增量合并（旧摘要 + 新段合并为新摘要条目）；
 * 摘要条目追加转录 + summary_entry_id 指针更新；摘要区只投最新一段。
 *
 * <p>摘要由模板拼装（真实现归 2.3 小模型——本 feature 用确定性拼装，剧本纪律：
 * 压缩机制本身是被测对象，摘要质量归模型侧）。
 */
@Component
public class TranscriptCompactor {

    private static final Logger log = LoggerFactory.getLogger(TranscriptCompactor.class);

    /**
     * 五段摘要的段标题（社交事实结构：谁在聊/聊了什么/结论与共识/情绪氛围/待办与悬念）
     */
    private static final String[] SUMMARY_SECTIONS =
            {"讨论参与者", "主要话题", "结论与共识", "情绪氛围", "待办与悬念"};

    /**
     * 单次压缩保留的近端条数（近端不进压缩区——保新鲜上下文逐字）
     */
    private static final int RECENT_KEEP = 30;

    private final SessionManager sessionManager;
    private final AgentSessionMapper sessionMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public TranscriptCompactor(SessionManager sessionManager,
                               AgentSessionMapper sessionMapper,
                               IdGenerator idGenerator,
                               Clock clock,
                               org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.sessionManager = sessionManager;
        this.sessionMapper = sessionMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 压缩一次会话（预防性通道；应急通道由 Run 侧以相同入口重试驱动）。
     *
     * @return 新摘要条目（已追加转录并更新指针）；无需压缩返回 null
     */
    public SummaryEntry compactIfNeeded(AgentSessionDO session, int estimatedTokens,
                                        int triggerLineTokens) {
        if (estimatedTokens <= triggerLineTokens) {
            return null;
        }
        List<AgentMessage> transcript = sessionManager.loadTranscript(session);
        if (transcript.size() <= RECENT_KEEP) {
            return null;   // 太短不值得压缩（空窗与近端全保留）
        }

        // 切点：压缩区 = [0, cutIndex)，近端 [cutIndex, size) 保留
        int cutIndex = transcript.size() - RECENT_KEEP;
        cutIndex = adjustCutToAvoidDanglingPair(transcript, cutIndex);

        List<AgentMessage> compacting = transcript.subList(0, cutIndex);
        if (compacting.isEmpty()) {
            return null;
        }

        // 增量合并：旧摘要（若在压缩区内）并入新摘要
        String oldSummaryText = null;
        List<AgentMessage> freshEntries = new ArrayList<>();
        for (AgentMessage message : compacting) {
            if (message instanceof SummaryEntry summary) {
                oldSummaryText = summary.summaryText();   // 只保留最新一段（摘要区唯一投放）
            } else {
                freshEntries.add(message);
            }
        }

        String summaryText = buildFiveSectionSummary(oldSummaryText, freshEntries);
        SummaryEntry newSummary = new SummaryEntry(
                Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 50,
                "compaction-" + session.getId(), summaryText,
                "覆盖 " + compacting.size() + " 条（至 " + compacting.get(compacting.size() - 1).messageId() + "）");
        sessionManager.appendTranscript(session, List.of(newSummary), null);
        sessionMapper.updateSummaryPointer(session.getId(), Long.parseLong(newSummary.messageId()));

        // 水位降档：持续高水位压缩后降到 quiet 落库（投影读 session 档位——多群各持各档；
        // 活跃群回 active 由群状态互动驱动，2.4 演进）
        sessionMapper.updateWindowLevel(session.getId(), AgentSessionDO.WINDOW_QUIET);
        log.info("会话压缩完成 sessionId={} 压缩 {} 条为新摘要，窗口降档 quiet",
                session.getId(), compacting.size());

        // 沉淀提示事件（2.2 发、2.4 消费——是否沉淀是记忆系统的自由）
        eventPublisher.publishEvent(new SedimentSuggestionEvent(
                session.getPlatform(), session.getBotId(), session.getGroupId(),
                newSummary.messageId(), compacting.size()));
        return newSummary;
    }

    /**
     * 切点防悬挂：cutIndex 落在「assistant(带工具调用) 与其配对 toolResult 之间」时后移
     * （切点永不落对中间——spec US7 验收场景 2）
     */
    int adjustCutToAvoidDanglingPair(List<AgentMessage> transcript, int cutIndex) {
        int adjusted = cutIndex;
        while (adjusted < transcript.size()) {
            AgentMessage at = transcript.get(adjusted);
            if (at instanceof AssistantMessage assistant
                    && assistant.toolCalls() != null && !assistant.toolCalls().isEmpty()) {
                // 该 assistant 的配对结果在后——切点移到配对结果之后
                boolean pairAhead = false;
                for (int j = adjusted + 1; j <= Math.min(adjusted + 8, transcript.size() - 1); j++) {
                    AgentMessage next = transcript.get(j);
                    if (next instanceof ToolResultMessage result
                            && assistant.toolCalls().stream()
                            .anyMatch(call -> call.callId().equals(result.callId()))) {
                        pairAhead = true;
                        adjusted = j + 1;
                        break;
                    }
                }
                if (pairAhead) {
                    continue;
                }
            }
            if (at instanceof ToolResultMessage toolResult) {
                // 结果在前调用在后（乱序防御）：切点前移一位让配对完整留在压缩区
                boolean callBehind = false;
                for (int j = adjusted - 1; j >= Math.max(0, adjusted - 8); j--) {
                    AgentMessage prev = transcript.get(j);
                    if (prev instanceof AssistantMessage assistant && assistant.toolCalls() != null
                            && assistant.toolCalls().stream()
                            .anyMatch(call -> call.callId().equals(toolResult.callId()))) {
                        callBehind = true;
                        break;
                    }
                }
                if (callBehind) {
                    adjusted++;   // 配对的调用在前——把本结果也划入近端（切点后移）
                    continue;
                }
            }
            break;
        }
        return Math.min(adjusted, transcript.size());
    }

    /**
     * 五段摘要拼装（确定性模板——真实现归 2.3 小模型，本 feature 保证机制与结构）
     */
    private String buildFiveSectionSummary(String oldSummaryText, List<AgentMessage> entries) {
        StringBuilder builder = new StringBuilder();
        if (oldSummaryText != null) {
            builder.append("【早前摘要并入】").append(oldSummaryText).append("\n\n");
        }
        for (int i = 0; i < SUMMARY_SECTIONS.length; i++) {
            builder.append(SUMMARY_SECTIONS[i]).append("：");
            switch (i) {
                case 0 -> builder.append(participantsOf(entries));
                case 1 -> builder.append(topicsOf(entries));
                case 2 -> builder.append("（模型摘要第一版：确定性拼装，结论提取归 2.3 小模型）");
                case 3 -> builder.append("（情绪氛围提取归 2.3 小模型）");
                case 4 -> builder.append("（待办提取归 2.3 小模型）");
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private String participantsOf(List<AgentMessage> entries) {
        List<String> speakers = new ArrayList<>();
        for (AgentMessage message : entries) {
            if (message instanceof cn.edu.scut.chatbot.loop.message.UserMessage user
                    && !speakers.contains(user.senderUserId())) {
                speakers.add(user.senderUserId());
            }
        }
        return speakers.isEmpty() ? "（无发言者）" : String.join("、", speakers);
    }

    private String topicsOf(List<AgentMessage> entries) {
        // 话题提取：取 user 消息文本前 30 字（确定性拼装——语义聚类归 2.3 小模型）
        List<String> samples = new ArrayList<>();
        for (AgentMessage message : entries) {
            if (message instanceof cn.edu.scut.chatbot.loop.message.UserMessage user
                    && user.parts() != null && !user.parts().isEmpty()
                    && user.parts().get(0) instanceof cn.edu.scut.chatbot.contract.message.TextPart textPart) {
                String text = textPart.text();
                samples.add(text.length() > 30 ? text.substring(0, 30) : text);
            }
            if (samples.size() >= 5) {
                break;
            }
        }
        return samples.isEmpty() ? "（无文本消息）" : String.join("；", samples);
    }
}
