package cn.edu.scut.chatbot.loop.events;

/**
 * 循环事件监听器：钩子参与决策、事件仅供观察。监听器外壳（infrastructure/eventbus）
 * 以有界队列实现本接口——只做快速入队，慢操作由后台消费者消化。
 */
@FunctionalInterface
public interface LoopEventListener {

    void onEvent(LoopEvent event);
}
