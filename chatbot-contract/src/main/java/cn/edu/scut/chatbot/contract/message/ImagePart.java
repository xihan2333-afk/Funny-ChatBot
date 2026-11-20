package cn.edu.scut.chatbot.contract.message;

/**
 * 图片段。OneBot 来源：image.url / file / file_size。
 *
 * <p>入方向 originalUrl 是平台时效 URL（QQ 媒体 URL 约两小时过期，须尽快转存）；
 * 出方向 Agent 给 storedRef（本地存储引用），originalUrl 为空，Sender 装载为 base64:// 内联。
 *
 * @param part           段类型多态标记，恒为 "image"
 * @param originalUrl    平台原始 URL，可空
 * @param storedRef      转存后的本地存储引用，可空（transferStatus=transferred 时有值）
 * @param transferStatus 转存状态短码，取值见 {@link MediaTransferStatusCodes}
 * @param fileName       文件名（OneBot 段的 file 字段，收方向常为本地缓存文件名）
 * @param fileSize       文件字节数，可空
 */
public record ImagePart(
        String part,
        String originalUrl,
        String storedRef,
        String transferStatus,
        String fileName,
        Long fileSize
) implements MessagePart {

    public static final String TYPE_IMAGE = "image";
}
