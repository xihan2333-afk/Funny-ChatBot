package cn.edu.scut.chatbot.loop;

/**
 * 流事件消费者：StreamFn 逐事件回调（十一种 ModelStreamEvent 按到达顺序推给循环）。
 */
@FunctionalInterface
public interface StreamConsumer {

    void onEvent(ModelStreamEvent event);
}
