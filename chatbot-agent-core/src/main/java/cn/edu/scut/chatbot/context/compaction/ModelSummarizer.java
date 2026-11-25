package cn.edu.scut.chatbot.context.compaction;

import cn.edu.scut.chatbot.llm.gateway.CallOnceResult;
import cn.edu.scut.chatbot.llm.gateway.LlmCallMeta;
import cn.edu.scut.chatbot.llm.gateway.ModelGateway;
import cn.edu.scut.chatbot.llm.slot.ModelCatalog;
import cn.edu.scut.chatbot.loop.GenerationParams;
import cn.edu.scut.chatbot.loop.LlmMessage;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 模型侧摘要实现（003-llm-api US7：summarize 槽位 callOnce 生成五段摘要）——
 * 替换 {@link DeterministicSummaryGenerator} 默认实现；压缩切点/水位/指针机制不动
 * （spec US7 场景 7）。
 *
 * <p>降级纪律：模型调用失败或空产出 → 回落确定性拼装（压缩是关键路径——应急压缩
 * 通道不能因模型侧不可用而失效）。
 */
@Component
public class ModelSummarizer implements SummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ModelSummarizer.class);

    private static final GenerationParams SUMMARY_PARAMS =
            new GenerationParams(0.3, 2048, false, Map.of());

    /**
     * 单条消息进摘要输入的文本截断（长工具结果不占满窗口）
     */
    private static final int ENTRY_TEXT_LIMIT = 200;

    private final ModelGateway gateway;

    private final SummaryGenerator fallback = new DeterministicSummaryGenerator();

    public ModelSummarizer(ModelGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String generate(String oldSummaryText, List<AgentMessage> compactingEntries) {
        try {
            CallOnceResult result = gateway.callOnce(ModelCatalog.SLOT_SUMMARIZE,
                    LlmCallMeta.of("summarize", null),
                    "你是群聊社交事实摘要器。把给定会话条目压缩为五段摘要，段标题固定为："
                            + "讨论参与者/主要话题/结论与共识/情绪氛围/待办与悬念，每段一行，直接输出摘要。",
                    List.of(LlmMessage.user(assembleInput(oldSummaryText, compactingEntries))),
                    SUMMARY_PARAMS);
            if (!result.failed() && result.text() != null && !result.text().isBlank()) {
                return result.text();
            }
            log.warn("压缩摘要模型侧失败，降级确定性拼装 errorCode={}",
                    result.failed() ? result.errorCode() : "(空产出)");
        } catch (Exception e) {
            log.warn("压缩摘要模型调用异常，降级确定性拼装", e);
        }
        return fallback.generate(oldSummaryText, compactingEntries);
    }

    private String assembleInput(String oldSummaryText, List<AgentMessage> entries) {
        StringBuilder input = new StringBuilder();
        if (oldSummaryText != null && !oldSummaryText.isBlank()) {
            input.append("【早前摘要（并入新摘要）】\n").append(oldSummaryText).append("\n\n");
        }
        input.append("【待压缩会话条目】\n");
        int lines = 0;
        for (AgentMessage message : entries) {
            String line = entryLine(message);
            if (line == null) {
                continue;
            }
            input.append(line).append('\n');
            if (++lines >= 200) {
                input.append("（其余条目省略）\n");
                break;
            }
        }
        return input.toString();
    }

    private String entryLine(AgentMessage message) {
        if (message instanceof UserMessage user) {
            return "用户" + user.senderUserId() + "：" + abbreviate(plainUserText(user));
        }
        if (message instanceof AssistantMessage assistant) {
            return "机器人：" + abbreviate(assistant.textContent());
        }
        if (message instanceof ToolResultMessage toolResult) {
            return "工具结果(" + toolResult.toolName() + ")："
                    + abbreviate(plainContentText(toolResult.content()));
        }
        return null;
    }

    private String plainContentText(List<? extends cn.edu.scut.chatbot.loop.message.ContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "（空）";
        }
        StringBuilder text = new StringBuilder();
        for (cn.edu.scut.chatbot.loop.message.ContentPart part : parts) {
            if (part instanceof cn.edu.scut.chatbot.loop.message.TextContent textContent) {
                text.append(textContent.text());
            }
        }
        return text.length() == 0 ? "（无文本段）" : text.toString();
    }

    private String plainUserText(UserMessage user) {
        if (user.parts() == null || user.parts().isEmpty()
                || !(user.parts().get(0) instanceof cn.edu.scut.chatbot.contract.message.TextPart textPart)) {
            return "（非文本段）";
        }
        return textPart.text();
    }

    private String abbreviate(String text) {
        if (text == null || text.isEmpty()) {
            return "（空）";
        }
        return text.length() > ENTRY_TEXT_LIMIT ? text.substring(0, ENTRY_TEXT_LIMIT) + "…" : text;
    }
}
