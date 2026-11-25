package cn.edu.scut.chatbot.context.media;

/**
 * 视觉描述端口（003-llm-api US7 端口化）：图片/表情包媒体引用 → 描述文本。
 * 投影层指纹命中时直接复用已有描述，未命中走本端口生成。
 *
 * <p>默认桩返回 null（投「描述未就绪」占位行）；真实现
 * {@link GatewayVisionDescriber}（vision_desc 槽位 callOnce 携带图片）就位后退位。
 * 描述失败不抛异常——返回 null 由投影降级占位行（视觉增强不阻塞投影）。
 */
@FunctionalInterface
public interface VisionDescriber {

    /**
     * @param mediaRef 媒体引用（storedRef 或表情包指纹——同为接入层可解析的引用形态）
     * @return 一句话描述；不可用或失败返回 null（调用方投占位行）
     */
    String describe(String mediaRef);
}
