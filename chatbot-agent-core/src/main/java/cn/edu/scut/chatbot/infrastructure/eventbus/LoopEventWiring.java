package cn.edu.scut.chatbot.infrastructure.eventbus;

import cn.edu.scut.chatbot.loop.events.LoopEventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 循环事件监听器的全局消费方注册口（T012 事件桥挂载面）：engine 各 Run 组装点经本工厂
 * 取监听器，工厂把已注册的全局消费方逐一挂进新监听器——观测桥（observability）依赖
 * infrastructure 是合法依赖边，engine 不感知 observability 也能把事件送达桥
 * （依赖方向：observability → infrastructure ← engine）。
 *
 * <p>未注册任何消费方时（无桥的单元测试与本地裸跑），工厂退化为裸监听器，行为与
 * 直接 new BoundedEventListener() 完全一致。
 */
@Component
public class LoopEventWiring {

    private final List<LoopEventListener> globalConsumers = new CopyOnWriteArrayList<>();

    /**
     * 注册全局消费方（观测桥启动时调用一次；监听器外壳保证快速入队，慢消费不拖累循环）
     */
    public void registerGlobalConsumer(LoopEventListener consumer) {
        globalConsumers.add(consumer);
    }

    /**
     * 建 Run 级监听器并挂上全部已注册消费方（调用方负责 Run 结束时 close——排空残余）
     */
    public BoundedEventListener newListener() {
        BoundedEventListener listener = new BoundedEventListener();
        for (LoopEventListener consumer : globalConsumers) {
            listener.addBackgroundConsumer(consumer);
        }
        return listener;
    }
}
