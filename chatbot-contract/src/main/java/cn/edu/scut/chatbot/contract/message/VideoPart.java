package cn.edu.scut.chatbot.contract.message;

/**
 * 视频段。OneBot 来源：video.url / file。字段集合与 {@link ImagePart} 相同。
 *
 * @param part           段类型多态标记，恒为 "video"
 * @param originalUrl    平台原始 URL，可空
 * @param storedRef      转存后的本地存储引用，可空
 * @param transferStatus 转存状态短码，取值见 {@link MediaTransferStatusCodes}
 * @param fileName       文件名
 * @param fileSize       文件字节数，可空
 */
public record VideoPart(
        String part,
        String originalUrl,
        String storedRef,
        String transferStatus,
        String fileName,
        Long fileSize
) implements MessagePart {

    public static final String TYPE_VIDEO = "video";
}
