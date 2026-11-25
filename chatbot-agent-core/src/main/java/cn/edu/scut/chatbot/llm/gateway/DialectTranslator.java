package cn.edu.scut.chatbot.llm.gateway;

/**
 * 方言翻译器契约（contracts/model-gateway.md 第 2 节）：把统一模型请求翻译为某一家
 * 协议方言（openai-compat / gemini），把流式响应翻译回十一种统一事件。
 *
 * <p>五步骨架（两方言同构）：组装请求 → 发送（共享 HttpClientHolder、连接级静默重试
 * 一次、读超时兜底）→ 解析 SSE（共用 SseParser）→ 翻译流内事件 → 终止
 * （Completed / Failed 绝不抛异常——内核纪律，StreamFn 契约的全部失败语义由翻译器承担）。
 *
 * <p>实现纪律：无状态单例 Bean——每次调用的累积缓冲全部在调用局部（并发调用互不串扰）；
 * 方言是协议能力随版本发布（注册表不参与配置刷新，research R7）。
 */
public interface DialectTranslator {

    /**
     * 本翻译器承接的方言名（ModelRef.DIALECT_* 之一——注册表的键）
     */
    String dialect();

    /**
     * 执行五步骨架。实现方保证：不抛任何异常（一切失败以 Failed 事件终结进流）、
     * 取消信号置位后停止读流以 Failed(llm.aborted) 终止、已推增量自然保留。
     */
    void translate(TranslateContext context);
}
