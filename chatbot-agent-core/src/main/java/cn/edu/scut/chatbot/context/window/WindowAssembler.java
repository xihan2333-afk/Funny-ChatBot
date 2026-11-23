package cn.edu.scut.chatbot.context.window;

import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.EventEntry;
import cn.edu.scut.chatbot.loop.message.SummaryEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 窗口组装器（data-model 第 13 节）：四分区（系统区 ≤15%/摘要区 ≤15%/对话区 ≥50%/现注区 10%）+
 * 锚定区（对话区尾部 20 条永不淘汰）+ weight 淘汰引擎 + 水位双档 + 超线硬切。
 *
 * <p>窗口是视图、历史全量只增不改：组装只决定「这次模型调用看到什么」。
 */
public class WindowAssembler {

    /**
     * 锚定区条数（对话区尾部永不淘汰）
     */
    public static final int ANCHOR_SIZE = 20;

    /**
     * weight 淘汰次序表（入库时按 kind 赋默认值；低者先出窗）
     */
    private static final int WEIGHT_MEMBER_CHANGE = 10;

    /**
     * 窗口组装结果：四区内容与淘汰观察口径
     */
    public record WindowPlan(
            List<AgentMessage> summaryZone,
            List<AgentMessage> conversationZone,
            List<AgentMessage> evicted,
            int estimatedTokens
    ) {
    }

    private final int activeBudgetTokens;

    private final int quietBudgetTokens;

    public WindowAssembler(int activeBudgetTokens, int quietBudgetTokens) {
        this.activeBudgetTokens = activeBudgetTokens;
        this.quietBudgetTokens = quietBudgetTokens;
    }

    /**
     * 组装窗口：对话区 = 全量转录按 weight 淘汰至预算内（锚定区尾部 20 条永不动）；
     * 摘要区 = 最新一段压缩摘要（summary_entry_id 指向者——由调用方传入，没有则空）。
     *
     * @param transcript    会话转录全量（按时间正序）
     * @param latestSummary 最新摘要条目（可空）
     * @param windowLevel   active / quiet 水位档
     */
    public WindowPlan assemble(List<AgentMessage> transcript, SummaryEntry latestSummary, String windowLevel) {
        int budget = "quiet".equals(windowLevel) ? quietBudgetTokens : activeBudgetTokens;
        // 预算分配：对话区保底 50%、摘要区 15%（系统区与现注区由调用方在总预算外组织）
        int conversationBudget = (int) (budget * 0.5);

        // 摘要区
        List<AgentMessage> summaryZone = new ArrayList<>();
        int summaryTokens = 0;
        if (latestSummary != null) {
            summaryZone.add(latestSummary);
            summaryTokens = TokenEstimator.estimate(latestSummary.summaryText());
        }

        // 对话区：非摘要条目
        List<AgentMessage> conversation = new ArrayList<>();
        for (AgentMessage message : transcript) {
            if (!(message instanceof SummaryEntry)) {
                conversation.add(message);
            }
        }

        // 淘汰引擎：从最旧端按 weight 升序出窗（同 weight 先旧后新）；
        // 锚定区（尾部 ANCHOR_SIZE 条）永不淘汰
        List<AgentMessage> evictable = conversation.size() > ANCHOR_SIZE
                ? new ArrayList<>(conversation.subList(0, conversation.size() - ANCHOR_SIZE))
                : new ArrayList<>();
        List<AgentMessage> anchored = conversation.size() > ANCHOR_SIZE
                ? new ArrayList<>(conversation.subList(conversation.size() - ANCHOR_SIZE, conversation.size()))
                : new ArrayList<>(conversation);

        List<AgentMessage> evicted = new ArrayList<>();
        int conversationTokens = conversation.stream()
                .mapToInt(message -> TokenEstimator.estimate(projectionTextOf(message)))
                .sum();

        while (conversationTokens > conversationBudget && !evictable.isEmpty()) {
            // 最该出窗的：weight 最低；同 weight 最旧
            AgentMessage victim = evictable.stream()
                    .min(Comparator.<AgentMessage>comparingInt(AgentMessage::weight)
                            .thenComparing(AgentMessage::occurredAt))
                    .orElseThrow();
            evictable.remove(victim);
            conversation.remove(victim);
            evicted.add(victim);
            conversationTokens -= TokenEstimator.estimate(projectionTextOf(victim));
        }

        // 对话区仍超线（锚定区撑满预算的极端形态）：从最旧端硬切——保命手段
        while (conversationTokens > conversationBudget && conversation.size() > 1) {
            AgentMessage cut = conversation.remove(0);
            evicted.add(cut);
            conversationTokens -= TokenEstimator.estimate(projectionTextOf(cut));
        }

        return new WindowPlan(summaryZone, List.copyOf(conversation), List.copyOf(evicted),
                summaryTokens + conversationTokens);
    }

    /**
     * 投影文本口径（估算依据——与投影产出的文本行一致即可，无需精确）
     */
    private String projectionTextOf(AgentMessage message) {
        if (message instanceof SummaryEntry summary) {
            return summary.summaryText();
        }
        if (message instanceof EventEntry event) {
            return event.summaryLine();
        }
        return String.valueOf(message);
    }

    /**
     * 水位判定：投影量超触发线（预算 − 输出上限 − 现注留量）即需要压缩
     */
    public boolean shouldCompact(int estimatedTokens, String windowLevel, int outputReserveTokens) {
        int budget = "quiet".equals(windowLevel) ? quietBudgetTokens : activeBudgetTokens;
        return estimatedTokens > budget - outputReserveTokens;
    }
}
