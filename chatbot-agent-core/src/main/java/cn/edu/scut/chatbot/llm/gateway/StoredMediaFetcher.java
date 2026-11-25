package cn.edu.scut.chatbot.llm.gateway;

/**
 * 转存媒体取字节端口（多模态装载的配套）：LlmMessage.images 里的 storedRef 是接入层
 * 媒体转存服务的引用，翻译器装载 image_url / inlineData 时经本端口取原始字节。
 *
 * <p>端口在 llm 侧、实现归接入层客户端（接入层媒体服务是独立部署面——当前未接入，
 * 装配给占位实现：取不到字节的图片跳过装载并告警，不阻塞调用）。存储职责在接入层
 * 媒体服务（research R13：llm 层不碰存储）。
 */
@FunctionalInterface
public interface StoredMediaFetcher {

    /**
     * 按转存引用取媒体字节（取不到返回 null——调用方跳过该图）
     */
    MediaBytes fetch(String storedRef);

    /**
     * 媒体字节与 MIME 类型（data URI 与 inlineData 的公共装载材料）
     */
    record MediaBytes(byte[] data, String mimeType) {
    }
}
