package cn.edu.scut.chatbot.llm.gateway;

/**
 * 转存媒体写端口（与 {@link StoredMediaFetcher} 读端口对偶）：能力产物（生图/合成语音
 * 字节）经接入层媒体转存服务落存储、返回 storedRef——出方向消息段引用它投递
 * （OneBot 11 无独立上传 API，Sender 读文件以 base64 内联）。
 *
 * <p>接入层客户端未接入前由装配方给占位实现（返回 null——调用方按转存失败降级，
 * 工具错误处理不阻塞循环）。
 */
@FunctionalInterface
public interface StoredMediaStore {

    /**
     * 存储媒体字节，返回存储引用；失败返回 null（不抛异常——降级语义交给调用方）
     */
    String store(byte[] data, String mimeType);
}
