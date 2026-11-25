package cn.edu.scut.chatbot.context.compaction;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.SummaryEntry;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>004 R1：压缩三写（摘要条目 / 指针 / 水位档）+ 沉淀工单落库收进同一事务
 * （{@code @Transactional}）——「压缩成功必有工单」是结构保证不靠事件；
 * SedimentSuggestionEvent 降级为 worker 即时唤醒信号（正确性不依赖、丢失由每日兜底兜住）。
 *
 * <p>摘要由模板拼装（真实现归 2.3 小模型——本 feature 用确定性拼装，剧本纪律：
 * 压缩机制本身是被测对象，摘要质量归模型侧）。
 */
@Component
public class TranscriptCompactor {

    private static final Logger log = LoggerFactory.getLogger(TranscriptCompactor.class);

    /**
     * 单次压缩保留的近端条数（近端不进压缩区——保新鲜上下文逐字）
     */
    private static final int RECENT_KEEP = 30;

    private final SessionManager sessionManager;
    private final AgentSessionMapper sessionMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final SummaryGenerator summaryGenerator;
    private final SedimentJobWriter sedimentJobWriter;
    private final cn.edu.scut.chatbot.memory.social.GroupStateService groupStateService;
    private final cn.edu.scut.chatbot.memory.social.TopicLifecycleService topicLifecycleService;

    public TranscriptCompactor(SessionManager sessionManager,
                               AgentSessionMapper sessionMapper,
                               IdGenerator idGenerator,
                               Clock clock,
                               org.springframework.context.ApplicationEventPublisher eventPublisher,
                               SummaryGenerator summaryGenerator,
                               SedimentJobWriter sedimentJobWriter,
                               cn.edu.scut.chatbot.memory.social.GroupStateService groupStateService,
                               cn.edu.scut.chatbot.memory.social.TopicLifecycleService topicLifecycleService) {
        this.sessionManager = sessionManager;
        this.sessionMapper = sessionMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.summaryGenerator = summaryGenerator;
        this.sedimentJobWriter = sedimentJobWriter;
        this.groupStateService = groupStateService;
        this.topicLifecycleService = topicLifecycleService;
    }

    /**
     * 压缩一次会话（预防性通道；应急通道由 Run 侧以相同入口重试驱动）。
     * 事务边界覆盖摘要三写 + 沉淀工单落库——要么都在要么都不在（R1）。
     *
     * @return 新摘要条目（已追加转录并更新指针）；无需压缩返回 null
     */
    @Transactional
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

        String summaryText = summaryGenerator.generate(oldSummaryText, freshEntries);
        SummaryEntry newSummary = new SummaryEntry(
                Long.toUnsignedString(idGenerator.nextId()), clock.instant(), 50,
                "compaction-" + session.getId(), summaryText,
                "覆盖 " + compacting.size() + " 条（至 " + compacting.get(compacting.size() - 1).messageId() + "）");
        sessionManager.appendTranscript(session, List.of(newSummary), null);
        sessionMapper.updateSummaryPointer(session.getId(), Long.parseLong(newSummary.messageId()));

        // 水位降档：持续高水位压缩后降到 quiet 落库（投影读 session 档位——多群各持各档；
        // 活跃群回 active 由群状态互动驱动，2.4 演进）
        sessionMapper.updateWindowLevel(session.getId(), AgentSessionDO.WINDOW_QUIET);

        // 同事务落沉淀工单（R1：「压缩成功必有工单」的结构保证——幂等键 = summaryEntryId，
        // 摘要与工单要么都在要么都不在）
        sedimentJobWriter.submitOnCompaction(
                new GroupKey(session.getPlatform(), session.getBotId(), session.getGroupId()),
                Long.parseUnsignedLong(newSummary.messageId()),
                new SedimentJobWriter.TranscriptRange(
                        Long.parseUnsignedLong(compacting.get(0).messageId()),
                        Long.parseUnsignedLong(compacting.get(compacting.size() - 1).messageId())));

        log.info("会话压缩完成 sessionId={} 压缩 {} 条为新摘要，窗口降档 quiet",
                session.getId(), compacting.size());

        // 沉淀提示事件（唤醒信号——正确性由事务内工单承担，丢失由每日兜底兜住）
        eventPublisher.publishEvent(new SedimentSuggestionEvent(
                session.getPlatform(), session.getBotId(), session.getGroupId(),
                newSummary.messageId(), compacting.size()));

        // 群状态与话题刷新（US8）：复用摘要产物零模型成本——氛围段进群状态、
        // 话题段驱动话题状态机（hot 未现 → 冷却计次）
        GroupKey groupKey = new GroupKey(
                session.getPlatform(), session.getBotId(), session.getGroupId());
        groupStateService.onCompactionRefreshed(groupKey,
                summarySection(summaryText, "主要话题"), summarySection(summaryText, "情绪氛围"));
        topicLifecycleService.onCompactionRefreshed(groupKey,
                splitTopics(summarySection(summaryText, "主要话题")));
        return newSummary;
    }

    /**
     * 摘要五段结构的段文本提取（"主要话题：xxx" 行——确定性拼装与模型摘要共用行前缀形态）
     */
    private String summarySection(String summaryText, String sectionTitle) {
        String prefix = sectionTitle + "：";
        for (String line : summaryText.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                String content = trimmed.substring(prefix.length()).trim();
                return content.isEmpty() ? null : content;
            }
        }
        return null;
    }

    /**
     * 话题段切分为话题列表（确定性拼装以「；」连接——同符号切分；单元素形态原样保留）
     */
    private List<String> splitTopics(String topicSection) {
        if (topicSection == null || topicSection.isBlank()) {
            return List.of();
        }
        List<String> topics = new ArrayList<>();
        for (String piece : topicSection.split("[；;]")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                topics.add(trimmed);
            }
        }
        return topics;
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

}
