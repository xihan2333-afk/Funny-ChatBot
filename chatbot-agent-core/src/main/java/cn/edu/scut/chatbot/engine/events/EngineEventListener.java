package cn.edu.scut.chatbot.engine.events;

/**
 * 引擎事件监听器（与 loop 的 LoopEventListener 同构的挂点契约）。
 *
 * <p>监听器两条纪律（与 BoundedEventListener 相同，结构保证非自觉）：
 * 只做快速入队（典型实现是有界队列外壳——慢操作由后台消费者消化）；
 * 异常不外溢（发布器捕获吞掉记日志，绝不影响引擎动作本身）。
 */
public interface EngineEventListener {

    void onEvent(EngineEvent event);
}
