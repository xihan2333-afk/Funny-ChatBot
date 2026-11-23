package cn.edu.scut.chatbot.context.media;

import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体投影器（T096）：图片与表情包的投影形态纪律——
 * 表情包指纹映射库（群级"指纹→描述文本"，视觉描述未就绪投占位行）、普通图片锚定区内投原图
 * 滚出后投描述行、Agent 自发图只留自述行。降级只随压缩发生（两次压缩间投影形态不变——
 * 由调用方在压缩时点调用 rebuildDescriptions，投影本身无副作用）。
 */
public class MediaProjector {

    /**
     * 表情包指纹 → 描述文本（群级映射库；管理员可修正——2.7 门面挂接）
     */
    private final Map<String, String> emojiFingerprints = new ConcurrentHashMap<>();

    /**
     * 表情包投影：映射命中投一行描述文本；未命中投占位行（描述生成挂点未就绪——R10 桩）
     */
    public String projectEmoji(String fingerprint) {
        String description = emojiFingerprints.get(fingerprint);
        return description != null
                ? "[表情包：" + description + "]"
                : "[表情包：描述未就绪，占位行]";
    }

    /**
     * 管理员修正指纹描述（机制在入口策略可运营）
     */
    public void correctFingerprint(String fingerprint, String description) {
        emojiFingerprints.put(fingerprint, description);
    }

    /**
     * 普通图片投影判定：锚定区内投原图形态、滚出后投描述行（降级只随压缩发生——
     * 压缩时点调用本方法重建；两次压缩之间调用返回 null 表示形态不变）
     */
    public String projectImage(boolean inAnchorZone, String imageRef, String description) {
        if (inAnchorZone) {
            return null;   // 投原图形态（投影层不做变形）
        }
        return "[图片" + (description != null ? "：" + description : "：描述未就绪") + "]";
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
