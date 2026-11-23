package cn.edu.scut.chatbot.loop;

import cn.edu.scut.chatbot.loop.message.ContentPart;
import cn.edu.scut.chatbot.loop.message.TextContent;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果（data-model 第 5 节）：content（文本/图片引用）、isError（业务正常分支，
 * 给模型看）、terminate 标记、结构化附件（如 sendMessage 回带 outboxId）。
 *
 * <p>terminate 语义：仅当同批全部结果携带 terminate 循环才收敛，混合批次照常继续。
 */
public record AgentToolResult(
        List<ContentPart> content,
        boolean isError,
        boolean terminate,
        Map<String, Object> attachments
) {

    public static AgentToolResult text(String text) {
        return new AgentToolResult(List.of(new TextContent(text)), false, false, Map.of());
    }

    public static AgentToolResult error(String errorText) {
        return new AgentToolResult(List.of(new TextContent(errorText)), true, false, Map.of());
    }
}
