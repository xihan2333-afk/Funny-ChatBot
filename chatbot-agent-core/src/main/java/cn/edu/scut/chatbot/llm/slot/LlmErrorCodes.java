package cn.edu.scut.chatbot.llm.slot;

/**
 * LLM 域统一错误码常量：点分字符串短码，llm.* 命名空间（宪法「异常与错误码」节）。
 *
 * <p>九个码随 Failed 终止事件进流（翻译器绝不抛异常——内核纪律）；与
 * AssistantMessage.stopReason 的关系：stopReason 保持 stop/toolUse/error/aborted
 * 四值不动，本组错误码是 error/aborted 的附属细分（llm_call_log.error_code 同口径）。
 *
 * <p>使用纪律：错误码只描述「系统哪里坏了」，不承载业务分支；ERROR 级日志必带
 * errorCode 与 traceId。
 */
public final class LlmErrorCodes {

    /**
     * 连接失败（含重试后）或流中断（非超时）——翻译器内连接级静默重试一次仍失败
     */
    public static final String NETWORK = "llm.network";
    /**
     * 连接超时或读流超时（读超时兜底默认 300 秒，可经 system_param 调整）
     */
    public static final String TIMEOUT = "llm.timeout";
    /**
     * HTTP 429（Gemini 另有 error.status=RESOURCE_EXHAUSTED 形态）——反馈凭证池冷却当次 key
     */
    public static final String RATE_LIMITED = "llm.rate_limited";
    /**
     * HTTP 401/403（Gemini 另有 UNAUTHENTICATED/PERMISSION_DENIED 形态）——反馈凭证池停用该 key
     */
    public static final String AUTH = "llm.auth";
    /**
     * 上下文超长（三重探测：错误文案模式 / prompt_tokens 超窗口 / 空输出且 length 停止）——上层应急压缩通道处置
     */
    public static final String CONTEXT_OVERFLOW = "llm.context_overflow";
    /**
     * 内容策略拦截（content_policy、safety 等枚举或文案命中）——不重试
     */
    public static final String CONTENT_POLICY = "llm.content_policy";
    /**
     * 其余 4xx/5xx API 错误——直接进流，Run 级重试由 Effects 驱动
     */
    public static final String API_ERROR = "llm.api_error";
    /**
     * 配置缺失（未知方言、槽位未绑定或解析失败——发生在翻译器之前）
     */
    public static final String CONFIG_MISSING = "llm.config_missing";
    /**
     * 取消信号置位（卡死检测/任务取消/群暂停）——停止读流、已推增量保留
     */
    public static final String ABORTED = "llm.aborted";

    private LlmErrorCodes() {
    }
}
