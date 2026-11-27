package cn.edu.scut.chatbot.context.media;

import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.management.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体投影器（T096）：图片与表情包的投影形态纪律——
 * 表情包指纹映射库（群级"指纹→描述文本"，视觉描述未就绪投占位行）、普通图片锚定区内投原图
 * 滚出后投描述行、Agent 自发图只留自述行。降级只随压缩发生（两次压缩间投影形态不变——
 * 由调用方在压缩时点调用 rebuildDescriptions，投影本身无副作用）。
 *
 * <p>持久化与管理面归 2.7/2.3（挂点已预留：correctFingerprint 即管理入口形态）；
 * 当前以进程内映射承载（第一版既定口径）。
 */
@Component
public class MediaProjector {

    /**
     * 表情包指纹 → 描述文本（群级映射库；管理员可修正——2.7 门面挂接）
     */
    private final Map<String, String> emojiFingerprints = new ConcurrentHashMap<>();

    /**
     * 视觉描述端口（指纹未命中时生成描述；桩返回 null 投占位行）
     */
    private final VisionDescriber visionDescriber;

    /**
     * 定界 + 注入检测组合件（US7 视觉描述通道——表情映射与图片降级描述归 VISION）。
     * setter 注入：未装配（隔离单测上下文）时回退静态定界，只丢检测不丢定界。
     */
    private cn.edu.scut.chatbot.security.delimit.DelimitGuard delimitGuard;
    private EmojiMappingMapper emojiMappingMapper;

    @Autowired(required = false)
    void setDelimitGuard(cn.edu.scut.chatbot.security.delimit.DelimitGuard guard) {
        this.delimitGuard = guard;
    }

    @Autowired(required = false)
    void setEmojiMappingMapper(EmojiMappingMapper emojiMappingMapper) {
        this.emojiMappingMapper = emojiMappingMapper;
    }

    public MediaProjector(VisionDescriber visionDescriber) {
        this.visionDescriber = visionDescriber;
    }

    public String projectEmoji(String fingerprint) {
        return projectEmoji("default", "default", "default", fingerprint);
    }

    /**
     * 表情包投影（群三元组隔离 + MySQL 事实源 + 内存缓存同步）
     */
    public String projectEmoji(String platform, String botId, String groupId, String fingerprint) {
        String cacheKey = cacheKey(platform, botId, groupId, fingerprint);
        String description = emojiFingerprints.get(cacheKey);
        if (description == null && emojiMappingMapper != null) {
            EmojiMappingDO row = emojiMappingMapper.selectByGroupFingerprint(platform, botId, groupId, fingerprint);
            if (row != null && row.getDescription() != null) {
                description = row.getDescription();
                emojiFingerprints.put(cacheKey, description);
            }
        }
        if (description == null) {
            description = visionDescriber.describe(fingerprint);
            if (description != null) {
                emojiFingerprints.put(cacheKey, description);
                if (emojiMappingMapper != null) {
                    EmojiMappingDO row = new EmojiMappingDO();
                    row.setPlatform(platform);
                    row.setBotId(botId);
                    row.setGroupId(groupId);
                    row.setFingerprint(fingerprint);
                    row.setDescription(description);
                    row.setSource("vision");
                    emojiMappingMapper.upsert(row);
                }
            }
        }
        return description != null
                ? "[表情包：" + delimited(description) + "]"
                : "[表情包：描述未就绪，占位行]";
    }

    public PageResult<EmojiMappingView> listEmojis(String platform, String botId, String groupId,
                                                   String keyword, int page, int pageSize) {
        if (emojiMappingMapper == null) {
            return PageResult.of(List.of(), 0, page, pageSize);
        }
        int p = PageResult.normalizePage(page);
        int ps = PageResult.normalizePageSize(pageSize);
        long total = emojiMappingMapper.countPaged(platform, botId, groupId, keyword);
        List<EmojiMappingView> items = emojiMappingMapper.selectPaged(
                        platform, botId, groupId, keyword, PageResult.offset(p, ps), ps).stream()
                .map(MediaProjector::toView)
                .toList();
        return PageResult.of(items, total, p, ps);
    }

    public EmojiMappingView correctEmojiDescription(long id, String description) {
        if (emojiMappingMapper == null) {
            throw new IllegalStateException("EmojiMappingMapper 未装配");
        }
        EmojiMappingDO existing = emojiMappingMapper.selectById(id);
        if (existing == null) {
            throw new cn.edu.scut.chatbot.exception.ChatBotException("mgmt.not_found", "表情包映射不存在 id=" + id);
        }
        emojiMappingMapper.updateDescription(id, description, "manual");
        emojiFingerprints.put(cacheKey(existing.getPlatform(), existing.getBotId(),
                existing.getGroupId(), existing.getFingerprint()), description);
        return toView(emojiMappingMapper.selectById(id));
    }

    private static EmojiMappingView toView(EmojiMappingDO e) {
        return new EmojiMappingView(
                e.getId(), e.getPlatform(), e.getBotId(), e.getGroupId(),
                e.getFingerprint(), e.getDescription(), e.getSource(),
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
    }

    public record EmojiMappingView(
            Long id, String platform, String botId, String groupId,
            String fingerprint, String description, String source, String updatedAt
    ) {
    }

    private static String cacheKey(String platform, String botId, String groupId, String fingerprint) {
        return platform + ":" + botId + ":" + groupId + "|" + fingerprint;
    }

    /**
     * 视觉描述定界（描述模型输出是不可信内容——图片里可能有字，字可能是指令）
     */
    private String delimited(String description) {
        return delimitGuard != null
                ? delimitGuard.wrap(description, cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.VISION)
                : cn.edu.scut.chatbot.security.delimit.DelimitHeaders.wrap(description,
                cn.edu.scut.chatbot.security.delimit.DelimitHeaders.Src.VISION);
    }

    /**
     * 管理员修正指纹描述（机制在入口策略可运营）
     */
    public void correctFingerprint(String fingerprint, String description) {
        emojiFingerprints.put(fingerprint, description);
    }

    /**
     * 普通图片投影判定：锚定区内投原图形态、滚出后投描述行（降级只随压缩发生——
     * 压缩时点调用本方法重建；两次压缩之间调用返回 null 表示形态不变）。
     * 描述缺失时经视觉描述端口补充。
     */
    public String projectImage(boolean inAnchorZone, String imageRef, String description) {
        if (inAnchorZone) {
            return null;   // 投原图形态（投影层不做变形）
        }
        if (description == null) {
            description = visionDescriber.describe(imageRef);
        }
        return "[图片" + (description != null ? "：" + delimited(description) : "：描述未就绪") + "]";
    }

    /**
     * Agent 自发图只留自述行（转录里 assistant 消息的图片段以自述文本呈现）
     */
    public String projectAgentImage(AgentMessage message) {
        if (message instanceof AssistantMessage assistant && assistant.textContent() != null) {
            return assistant.textContent();
        }
        return "[Agent 发送了一张图片]";
    }

    /**
     * 当前映射库大小（观测口径）
     */
    public int fingerprintCount() {
        return emojiFingerprints.size();
    }
}
