package cn.edu.scut.chatbot.infrastructure.qdrant;

/**
 * Qdrant 薄客户端错误码（点分短码，风格与 EngineErrorCodes / LlmErrorCodes 一致）：
 * 只描述「系统哪里坏了」，不承载业务分支。
 */
public final class QdrantErrorCodes {

    private QdrantErrorCodes() {
    }

    /**
     * 网络不通 / 超时 / 上游 5xx / 请求被拒——可重试（调用方按「索引层可失败、
     * 事实源在 MySQL」降级：写入失败重试超限告警，检索失败 isError 不杀循环）
     */
    public static final String QDRANT_UNAVAILABLE = "memory.qdrant.unavailable";

    /**
     * 上游响应不是合法 JSON 或结构缺字段——可重试（换实例可能自愈）
     */
    public static final String QDRANT_BAD_RESPONSE = "memory.qdrant.bad_response";
}
