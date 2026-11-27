package cn.edu.scut.chatbot.engine.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 引擎事件发布器：发射方（intake / gate / turn / task / effect / outbox 各组件）经
 * setter 注入本发布器，同步 fan-out 给注册的监听器。观测桥与管理面都挂在这里
 * （research R3：一次建设两处消费）。
 *
 * <p>发布器自身不建队列——快速消化是监听器的责任（典型监听器是有界队列外壳，
 * onEvent 只有 offer）。监听器异常在此吞掉记日志：事件通知失败不影响引擎动作。
 */
@Component
public class EngineEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EngineEventPublisher.class);

    private final List<EngineEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(EngineEventListener listener) {
        listeners.add(listener);
    }

    public void publish(EngineEvent event) {
        for (EngineEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("引擎事件监听器异常（不外溢） event={}", event.getClass().getSimpleName(), e);
            }
        }
    }
}
