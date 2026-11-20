package cn.edu.scut.chatbot.contract.message;

/**
 * 语音段。OneBot 来源：record.url / file / file_size。字段集合与 {@link ImagePart} 相同。
 *
 * @param part           段类型多态标记，恒为 "audio"
 * @param originalUrl    平台原始 URL，可空
 * @param storedRef      转存后的本地存储引用，可空
 * @param transferStatus 转存状态短码，取值见 {@link MediaTransferStatusCodes}
 * @param fileName       文件名
 * @param fileSize       文件字节数，可空
 */
public record AudioPart(
        String part,
        String originalUrl,
        String storedRef,
        String transferStatus,
        String fileName,
        Long fileSize
) implements MessagePart {

    public static final String TYPE_AUDIO = "audio";
}
