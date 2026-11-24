package cn.edu.scut.chatbot.loop.transcript;

import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 发言三结局判定（契约 loop-kernel.md 第 5 节）：
 * <ol>
 *   <li>调用过 sendMessage（非 isError）→ 正常交付，run_end 报告发言清单（outboxId 列表）</li>
 *   <li>从未调用但有裸文本 → 取最后一条非残缺整段兜底投递（裸文本只是腹稿）</li>
 *   <li>全无 → 沉默收尾（SILENT 是合法终态——下游否决上游）</li>
 * </ol>
 *
 * <p>残缺纪律：带 stopReason=error/aborted 标记的 AssistantMessage 可留转录给模型看，
 * 永不作为发言投递、不参与兜底。
 */
public final class SpeechOutcomeJudger {

    /**
     * 对群发言唯一出口的工具名（一切对外发言只有这一个出口——宪法第三条）
     */
    public static final String SEND_MESSAGE_TOOL = "sendMessage";

    private SpeechOutcomeJudger() {
    }

    /**
     * @param newMessages 本次 Run 新产生集合（不用差集计算——双转录纪律）
     */
    public static SpeechOutcome judge(List<AgentMessage> newMessages) {
        List<String> outboxIds = new ArrayList<>();
        for (AgentMessage message : newMessages) {
            if (message instanceof ToolResultMessage toolResult
                    && SEND_MESSAGE_TOOL.equals(toolResult.toolName())
                    && !toolResult.isError()
                    && toolResult.attachments() != null
                    && toolResult.attachments().get("outboxId") != null) {
                outboxIds.add(String.valueOf(toolResult.attachments().get("outboxId")));
            }
        }
        if (!outboxIds.isEmpty()) {
            return new SpeechOutcome(outboxIds, null, false, null);
        }
        // 裸文本兜底：最后一条非残缺、有文本的 assistant 消息
        for (int i = newMessages.size() - 1; i >= 0; i--) {
            AgentMessage message = newMessages.get(i);
            if (message instanceof AssistantMessage assistant
                    && !assistant.isDefective()
                    && assistant.textContent() != null
                    && !assistant.textContent().isBlank()) {
                return new SpeechOutcome(List.of(), assistant.textContent(), false, null);
            }
        }
        return new SpeechOutcome(List.of(), null, true, "全程无发言（沉默收尾）");
    }

    /**
     * 三结局统一形态：outboxIds 非空=正常交付；fallbackText 非空=兜底；silent=沉默
     */
    public record SpeechOutcome(
            List<String> outboxIds,
            String fallbackText,
            boolean silent,
            String silentReason
    ) {
    }
}
