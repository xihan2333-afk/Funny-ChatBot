package cn.edu.scut.chatbot.llm.gateway;

import cn.edu.scut.chatbot.loop.*;

import java.util.List;

/**
 * 模型网关统一入口（contracts/model-gateway.md 第 1 节）：llm 包对外的主接口面，
 * 一切调用方（engine 流式装配、callOnce 直连场景）都从这里进。
 *
 * <p>路由纯度：网关只做查表与派发，不处理业务逻辑；重试、降级、换凭证全是上层的事。
 */
public interface ModelGateway {

    /**
     * 流式调用：按 request.model().dialect() 查方言注册表派发；事件实时透传给
     * consumer（十一种 ModelStreamEvent）。StreamFn 契约的全部纪律（绝不抛异常、
     * 取消经 CancellationSignal）由本实现承担。
     */
    void stream(ModelRequest request, StreamConsumer consumer, CancellationSignal signal);

    /**
     * 单次调用便捷方法：内部完成槽位解析 + 凭证解析 + 翻译器五步与事件流（复用全部
     * 机制含多模态装载与思考契约），外收集 Completed 返回文本与用量；槽位解析失败返回
     * 错误结果而非异常（L1 场景由调用方保守 ignore）。同样经 CostRecorder 落账。
     */
    CallOnceResult callOnce(String slotCode, LlmCallMeta meta,
                            String systemPrompt, List<LlmMessage> messages,
                            GenerationParams params);
}
