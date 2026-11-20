package cn.edu.scut.chatbot.contract.in;

/**
 * RawEvent 兜底原因短码（data-model.md 第 10 节）。
 */
public final class RawEventReasonCodes {

    /**
     * 报文不是合法 JSON
     */
    public static final String ILLEGAL_JSON = "illegal_json";

    /**
     * JSON 合法但结构不认识（未知 post_type / 未知事件类型）
     */
    public static final String UNKNOWN_STRUCTURE = "unknown_structure";

    /**
     * 翻译过程内部异常（原文已留档，排障用）
     */
    public static final String INTERNAL_ERROR = "internal_error";

    private RawEventReasonCodes() {
    }
}
