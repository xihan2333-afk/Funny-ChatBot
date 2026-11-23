package cn.edu.scut.chatbot.context;

import cn.edu.scut.chatbot.context.window.WindowAssembler;
import cn.edu.scut.chatbot.loop.LlmMessage;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.DeliveryReportEntry;
import cn.edu.scut.chatbot.loop.message.EventEntry;
import cn.edu.scut.chatbot.loop.message.LinkCardEntry;
import cn.edu.scut.chatbot.loop.message.SummaryEntry;
import cn.edu.scut.chatbot.loop.message.SystemNote;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整投影（T098，替换 Phase 5 基准直投）：四操作——筛选（窗口淘汰已出窗条目不投）、
 * 变形（扩展条目翻译、图片降级形态）、聚合（连续工具结果聚合的演进点）、截断（窗口预算内）。
 * 窗口组装（四分区 + 锚定 + weight 淘汰）经 WindowAssembler。
 */
@Component
public class ContextAwareProjector implements LoopHooks.ConvertToLlmHook {

    private final WindowAssembler windowAssembler;

    /**
     * 当前窗口档（active/quiet——由压缩任务调整；投影时读最新档）
     */
    private volatile String windowLevel = "active";

    /**
     * 最新摘要指针（压缩任务更新；null=尚无摘要）
     */
    private volatile SummaryEntry latestSummary;

    public ContextAwareProjector(WindowAssembler windowAssembler) {
        this.windowAssembler = windowAssembler;
    }

    /**
     * 压缩任务回写窗口状态（新摘要条目 + 水位档）
     */
    public void updateWindow(String windowLevel, SummaryEntry latestSummary) {
        this.windowLevel = windowLevel;
        this.latestSummary = latestSummary;
    }

    @Override
    public List<LlmMessage> convert(List<AgentMessage> workingTranscript) {
        // 窗口组装（筛选操作：evicted 条目不投；锚定区与摘要区在窗）
        WindowAssembler.WindowPlan plan = windowAssembler.assemble(
                workingTranscript, latestSummary, windowLevel);

        List<LlmMessage> projected = new ArrayList<>();
        // 摘要区（只投最新一段）
        for (AgentMessage message : plan.summaryZone()) {
            projected.add(translate(message));
        }
        // 对话区（逐字历史投影）
        for (AgentMessage message : plan.conversationZone()) {
            projected.add(translate(message));
        }
        return projected;
    }

    /**
     * 变形操作：扩展条目翻译 + 基准条目直投（基准三类的直投逻辑复用 TranscriptProjector 的
     * 翻译规则——以组合方式复用而非继承）
     */
    private LlmMessage translate(AgentMessage message) {
        if (message instanceof SummaryEntry summary) {
            return LlmMessage.user(cn.edu.scut.chatbot.loop.message.SummaryEntry.PROJECTION_HEADER
                    + summary.summaryText());
        }
        if (message instanceof DeliveryReportEntry report) {
            if ("success".equals(report.status())) {
                return LlmMessage.user("（系统提示：上一条消息已成功送达）");
            }
            return LlmMessage.user("[系统提示] 你刚才的一条消息发送失败："
                    + (report.failureDetail() != null ? report.failureDetail() : report.failureKind()));
        }
        if (message instanceof EventEntry event) {
            return LlmMessage.user("[群事件] " + event.summaryLine());
        }
        if (message instanceof SystemNote note) {
            return LlmMessage.user(note.promptText());
        }
        if (message instanceof LinkCardEntry card) {
            return LlmMessage.user("[链接] " + (card.title() != null ? card.title() : card.normalizedUrl())
                    + (card.siteName() != null ? "（" + card.siteName() + "）" : "")
                    + (card.summary() != null ? "：" + card.summary() : ""));
        }
        if (message instanceof UserMessage || message instanceof cn.edu.scut.chatbot.loop.message.AssistantMessage
                || message instanceof ToolResultMessage) {
            return new TranscriptProjector().convert(List.of(message)).get(0);
        }
        return LlmMessage.user("[未知条目形态]");
    }
}
