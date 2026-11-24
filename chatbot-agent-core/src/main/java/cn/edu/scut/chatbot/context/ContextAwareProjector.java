package cn.edu.scut.chatbot.context;

import cn.edu.scut.chatbot.context.media.MediaProjector;
import cn.edu.scut.chatbot.context.window.WindowAssembler;
import cn.edu.scut.chatbot.loop.LlmMessage;
import cn.edu.scut.chatbot.loop.hooks.LoopHooks;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.SummaryEntry;
import cn.edu.scut.chatbot.loop.transcript.TranscriptProjector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整投影（T098，替换 Phase 5 基准直投）：四操作——筛选（窗口淘汰已出窗条目不投）、
 * 变形（扩展条目翻译、图片降级形态）、聚合（连续工具结果聚合的演进点）、截断（窗口预算内）。
 * 窗口组装（四分区 + 锚定 + weight 淘汰）经 WindowAssembler。
 *
 * <p>窗口档位按会话传入（agent_session.window_level——多群各持各档，不共享进程级状态）；
 * 最新摘要从转录自身提取（摘要条目只增不改，最新即当前有效段——无需外部指针）。
 */
@Component
public class ContextAwareProjector implements LoopHooks.ConvertToLlmHook {

    /**
     * 窗口档位默认值（无会话上下文的形态——Worker）
     */
    public static final String LEVEL_ACTIVE = "active";

    private final WindowAssembler windowAssembler;
    private final MediaProjector mediaProjector;

    public ContextAwareProjector(WindowAssembler windowAssembler, MediaProjector mediaProjector) {
        this.windowAssembler = windowAssembler;
        this.mediaProjector = mediaProjector;
    }

    /**
     * 无档位形态的投影（默认 active 档）
     */
    @Override
    public List<LlmMessage> convert(List<AgentMessage> workingTranscript) {
        return convert(workingTranscript, LEVEL_ACTIVE);
    }

    /**
     * 按会话窗口档投影（Run 组装时以闭包绑定该群的档位——Run 内档位固定，快照纪律）
     */
    public List<LlmMessage> convert(List<AgentMessage> workingTranscript, String windowLevel) {
        // 窗口组装（筛选操作：evicted 条目不投；锚定区与摘要区在窗）
        WindowAssembler.WindowPlan plan = windowAssembler.assemble(
                workingTranscript, latestSummaryOf(workingTranscript), windowLevel);

        List<LlmMessage> projected = new ArrayList<>();
        // 窗口头部标注：有条目被淘汰/硬切出窗时，模型知道更早内容存在（截断自描述）
        if (!plan.evicted().isEmpty()) {
            projected.add(LlmMessage.user("[上下文说明] 更早的 " + plan.evicted().size()
                    + " 条未逐字展示" + (plan.summaryZone().isEmpty() ? "" : "（已压缩为上方摘要）")));
        }
        // 摘要区（只投最新一段）
        for (AgentMessage message : plan.summaryZone()) {
            projected.add(translate(message));
        }
        // 对话区（锚定区内图片投原图形态、锚定区外降级为描述行；
        // 返回 null 的条目不投——如成功回执：发送成功是常态不需要提醒）
        List<AgentMessage> conversation = plan.conversationZone();
        int anchorStart = Math.max(0, conversation.size() - WindowAssembler.ANCHOR_SIZE);
        for (int i = 0; i < conversation.size(); i++) {
            boolean inAnchorZone = i >= anchorStart;
            LlmMessage line = translateOrNull(conversation.get(i), inAnchorZone);
            if (line != null) {
                projected.add(line);
            }
        }
        return projected;
    }

    /**
     * 最新摘要条目（转录只增不改——occurredAt 最大即当前有效段）
     */
    private SummaryEntry latestSummaryOf(List<AgentMessage> workingTranscript) {
        SummaryEntry latest = null;
        for (AgentMessage message : workingTranscript) {
            if (message instanceof SummaryEntry summary
                    && (latest == null || !summary.occurredAt().isBefore(latest.occurredAt()))) {
                latest = summary;
            }
        }
        return latest;
    }

    /**
     * 变形操作默认形态（无锚定区信息——非对话区条目；不投条目按「成功回执」处理返回 null）
     */
    private LlmMessage translate(AgentMessage message) {
        return translateOrNull(message, true);
    }

    /**
     * 变形操作：扩展条目翻译 + 基准条目直投（基准条目的直投逻辑复用 TranscriptProjector 的
     * 翻译规则——以组合方式复用而非继承）。
     * 返回 null 表示该条目本次不投（默认形态：DeliveryReportEntry 成功——发送成功是常态；
     * 失败投一行失败提示）
     *
     * @param inAnchorZone 对话区尾部锚定区内（图片投原图形态；区外降级为描述行）
     */
    private LlmMessage translateOrNull(AgentMessage message, boolean inAnchorZone) {
        if (message instanceof SummaryEntry) {
            return new TranscriptProjector().convert(List.of(message)).get(0);
        }
        if (message instanceof cn.edu.scut.chatbot.loop.message.DeliveryReportEntry report) {
            if ("success".equals(report.status())) {
                return null;   // 成功不投影——调用方过滤
            }
            return new TranscriptProjector().convert(List.of(message)).get(0);
        }
        if (message instanceof cn.edu.scut.chatbot.loop.message.UserMessage user) {
            return LlmMessage.user(renderUserWithMedia(user, inAnchorZone));
        }
        return new TranscriptProjector().convert(List.of(message)).get(0);
    }

    /**
     * 群友消息的媒体形态投影：锚定区内图片/表情包按 MediaProjector 投形态，
     * 区外图片降级为描述行（降级只随压缩发生的窗口视图侧表现）
     */
    private String renderUserWithMedia(cn.edu.scut.chatbot.loop.message.UserMessage user,
                                       boolean inAnchorZone) {
        StringBuilder builder = new StringBuilder();
        for (var part : user.parts()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            if (part instanceof cn.edu.scut.chatbot.contract.message.TextPart textPart) {
                builder.append(textPart.text());
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.AtPart atPart) {
                builder.append(cn.edu.scut.chatbot.contract.message.AtPart.TARGET_ALL
                        .equals(atPart.targetUserId()) ? "@全体成员" : "@" + atPart.targetUserId());
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.ImagePart imagePart) {
                String degraded = mediaProjector.projectImage(inAnchorZone,
                        imagePart.storedRef() != null ? imagePart.storedRef()
                                : imagePart.originalUrl(), null);
                builder.append(degraded != null ? degraded : "[图片]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.FacePart) {
                builder.append("[表情]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.QuotePart quotePart) {
                builder.append("[引用消息 ").append(quotePart.referencedMessageId()).append("]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.AudioPart) {
                builder.append("[语音]");
            } else if (part instanceof cn.edu.scut.chatbot.contract.message.VideoPart) {
                builder.append("[视频]");
            } else {
                builder.append("[未支持的消息段]");
            }
        }
        return user.senderUserId() + " 说：" + builder;
    }
}
