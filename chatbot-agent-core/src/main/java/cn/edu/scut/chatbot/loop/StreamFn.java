package cn.edu.scut.chatbot.loop;

/**
 * 模型调用的唯一形态（契约 loop-kernel.md 第 1 节）：绝不抛异常，一切失败（网络、超时、
 * 限流、内容截断）编码为流内 {@link ModelStreamEvent.Failed} 终止事件——内核拿到的
 * 永远是流内消息而不是异常，这是刻意隔离（宪法第五条）。
 *
 * <p>llm 包（2.3 feature）实现本接口，依赖方向 llm → loop；本 feature 期间由测试域
 * ScriptedStreamFn 剧本假件驱动。
 */
public interface StreamFn {

    /**
     * 流式调用：增量事件实时推出（卡死检测与半截保留的依据）。
     *
     * @param request  统一消息投影、工具声明、生成参数、已解析凭证
     * @param consumer 逐事件回调（十一种 ModelStreamEvent）
     * @param signal   取消唯一通道：置位后实现方必须停止读流、以 Failed(aborted) 终止；
     *                 调用方永不 interrupt 推理线程（宪法实现规约）
     */
    void stream(ModelRequest request, StreamConsumer consumer, CancellationSignal signal);
}
