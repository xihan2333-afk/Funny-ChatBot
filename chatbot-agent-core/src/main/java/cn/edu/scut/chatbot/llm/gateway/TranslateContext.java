package cn.edu.scut.chatbot.llm.gateway;

import cn.edu.scut.chatbot.loop.CancellationSignal;
import cn.edu.scut.chatbot.loop.ModelRequest;
import cn.edu.scut.chatbot.loop.StreamConsumer;

/**
 * 翻译器调用上下文（contracts/model-gateway.md 第 2 节）：一次模型调用的全部输入——
 * 统一请求、事件出口与取消通道。
 *
 * <p>翻译器只归一错误码推 Failed，不感知凭证池——反馈联动（rate_limited 冷却、
 * auth 停用）由网关观察 Failed 事件时对 CredentialPool 触发，联动内聚网关
 * （GatewayStreamFn.withCredentialFeedback）。
 */
public record TranslateContext(
        ModelRequest request,
        StreamConsumer consumer,
        CancellationSignal signal
) {
}
