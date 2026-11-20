package cn.edu.scut.chatbot.contract.envelope;

/**
 * 平台原始报文的留档引用：指向 raw_event_log 表中的一行（data-model.md 第 2 节）。
 *
 * <p>信封不携带原文本体——大报文不进队列是既定纪律；需要原文时凭本引用到留档表取。
 *
 * @param platform      同信封 platform
 * @param rawEventId    raw_event_log 自增 id 的字符串化
 * @param payloadFormat 留档原文格式，第一版恒为 "json"（预留二进制形态）
 */
public record RawEventRef(
        String platform,
        String rawEventId,
        String payloadFormat
) {

    /**
     * 留档原文格式的第一版取值：JSON 文本
     */
    public static final String FORMAT_JSON = "json";
}
