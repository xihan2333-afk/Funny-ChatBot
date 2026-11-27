package cn.edu.scut.chatbot.context.media;

import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import org.springframework.stereotype.Component;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDelimitGuard(cn.edu.scut.chatbot.security.delimit.DelimitGuard guard) {
        this.delimitGuard = guard;
    }

    public MediaProjector(VisionDescriber visionDescriber) {
        this.visionDescriber = visionDescriber;
    }

    /**
     * 表情包投影：指纹命中复用已有描述；未命中经视觉描述端口生成并缓存；
     * 端口不可用或失败投占位行。描述文本来自视觉模型（不可信）——
     * 以 untrusted 标签定界（US7 表情映射描述通道）
     */
    public String projectEmoji(String fingerprint) {
        String description = emojiFingerprints.get(fingerprint);
        if (description == null) {
            description = visionDescriber.describe(fingerprint);
            if (description != null) {
                // 命中即缓存（同指纹后续投影复用——不重复调用模型）
                emojiFingerprints.put(fingerprint, description);
            }
        }
        return description != null
                ? "[表情包：" + delimited(description) + "]"
                : "[表情包：描述未就绪，占位行]";
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
