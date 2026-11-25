package cn.edu.scut.chatbot.context.compaction;

import cn.edu.scut.chatbot.loop.message.AgentMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性摘要拼装（002 既有实现的端口化搬运——默认实现，EngineConfiguration 以
 *
 * @ConditionalOnMissingBean 装配，真模型实现就位后退位）：五段结构 + 参与者/话题
 * 确定性提取，其余段留占位说明。模型侧摘要不可用时的降级形态。
 */
public class DeterministicSummaryGenerator implements SummaryGenerator {

    /**
     * 五段摘要的段标题（社交事实结构：谁在聊/聊了什么/结论与共识/情绪氛围/待办与悬念）
     */
    private static final String[] SUMMARY_SECTIONS =
            {"讨论参与者", "主要话题", "结论与共识", "情绪氛围", "待办与悬念"};

    @Override
    public String generate(String oldSummaryText, List<AgentMessage> entries) {
        StringBuilder builder = new StringBuilder();
        if (oldSummaryText != null) {
            builder.append("【早前摘要并入】").append(oldSummaryText).append("\n\n");
        }
        for (int i = 0; i < SUMMARY_SECTIONS.length; i++) {
            builder.append(SUMMARY_SECTIONS[i]).append("：");
            switch (i) {
                case 0 -> builder.append(participantsOf(entries));
                case 1 -> builder.append(topicsOf(entries));
                case 2 -> builder.append("（确定性拼装——结论提取归摘要模型）");
                case 3 -> builder.append("（情绪氛围提取归摘要模型）");
                case 4 -> builder.append("（待办提取归摘要模型）");
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
        // 话题提取：取 user 消息文本前 30 字（确定性拼装——语义聚类归摘要模型）
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
