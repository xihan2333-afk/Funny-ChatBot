package cn.edu.scut.chatbot.loop.transcript;

import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.AudioPart;
import cn.edu.scut.chatbot.contract.message.FacePart;
import cn.edu.scut.chatbot.contract.message.ImagePart;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.QuotePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.contract.message.VideoPart;
import cn.edu.scut.chatbot.loop.LlmMessage;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.DeliveryReportEntry;
import cn.edu.scut.chatbot.loop.message.EventEntry;
import cn.edu.scut.chatbot.loop.message.LinkCardEntry;
import cn.edu.scut.chatbot.loop.message.SummaryEntry;
import cn.edu.scut.chatbot.loop.message.SystemNote;
import cn.edu.scut.chatbot.loop.message.ToolResultMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 转录投影基准实现（Phase 5 首挂接；Phase 9 T098 替换为完整四操作投影——
 * 筛选/变形/聚合/截断 + 扩展条目翻译 + 窗口组装）。
 *
 * <p>基准条目直投：user → user 角色、assistant → assistant 角色（含工具调用段）、
 * toolResult → tool 角色（callId 配对）。扩展条目按 data-model 第 2 节的默认投影形态
 * 翻译为 user 角色文本行。
 */
public final class TranscriptProjector implements LoopHooks.ConvertToLlmHook {

    @Override
    public List<LlmMessage> convert(List<AgentMessage> workingTranscript) {
        List<LlmMessage> projected = new ArrayList<>(workingTranscript.size());
        for (AgentMessage message : workingTranscript) {
            switch (message) {
                case UserMessage user -> projected.add(LlmMessage.user(renderUser(user)));
                case AssistantMessage assistant -> projected.add(LlmMessage.assistant(
                        assistant.textContent(), assistant.toolCalls()));
                case ToolResultMessage toolResult -> projected.add(LlmMessage.toolResult(
                        toolResult.callId(), renderContent(toolResult), toolResult.isError()));
                case DeliveryReportEntry report -> {
                    if (!"success".equals(report.status())) {
                        projected.add(LlmMessage.user("[系统提示] 你刚才的一条消息发送失败："
                                + (report.failureDetail() != null ? report.failureDetail()
                                : report.failureKind())));
                    }
                    // 成功不投影——发送成功是常态不需要提醒
                }
                case EventEntry event -> projected.add(LlmMessage.user("[群事件] " + event.summaryLine()));
                case SystemNote note -> projected.add(LlmMessage.user(note.promptText()));
                case LinkCardEntry card -> projected.add(LlmMessage.user(renderLinkCard(card)));
                case SummaryEntry summary -> projected.add(LlmMessage.user(
                        SummaryEntry.PROJECTION_HEADER + summary.summaryText()));
            }
        }
        return projected;
    }

    private String renderUser(UserMessage user) {
        StringBuilder builder = new StringBuilder();
        for (MessagePart part : user.parts()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            if (part instanceof TextPart textPart) {
                builder.append(textPart.text());
            } else if (part instanceof AtPart atPart) {
                builder.append(AtPart.TARGET_ALL.equals(atPart.targetUserId())
                        ? "@全体成员" : "@" + atPart.targetUserId());
            } else if (part instanceof ImagePart) {
                builder.append("[图片]");
            } else if (part instanceof FacePart) {
                builder.append("[表情]");
            } else if (part instanceof QuotePart quotePart) {
                builder.append("[引用消息 ").append(quotePart.referencedMessageId()).append("]");
            } else if (part instanceof AudioPart) {
                builder.append("[语音]");
            } else if (part instanceof VideoPart) {
                builder.append("[视频]");
            } else {
                builder.append("[未支持的消息段]");
            }
        }
        return user.senderUserId() + " 说：" + builder;
    }

    private String renderContent(ToolResultMessage toolResult) {
        StringBuilder builder = new StringBuilder();
        for (var content : toolResult.content()) {
            if (content instanceof cn.edu.scut.chatbot.loop.message.TextContent text) {
                builder.append(text.text());
            } else if (content instanceof cn.edu.scut.chatbot.loop.message.ImageContent) {
                builder.append("[图片]");
            }
        }
        return builder.toString();
    }

    private String renderLinkCard(LinkCardEntry card) {
        String title = card.title() != null ? card.title() : card.normalizedUrl();
        String site = card.siteName() != null ? "（" + card.siteName() + "）" : "";
        String summary = card.summary() != null ? "：" + card.summary() : "";
        return "[链接] " + title + site + summary;
    }
}
