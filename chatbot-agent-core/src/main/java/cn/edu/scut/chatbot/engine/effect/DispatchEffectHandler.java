package cn.edu.scut.chatbot.engine.effect;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.outbox.StreamDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * dispatch 处理器：出事件投递出流（行已先行落库——先落库再投递纪律；
 * XADD 失败抛可重试异常由 Effects 退避，行不删动作不丢）。
 */
@Component
public class DispatchEffectHandler implements EffectHandler {

    private final StreamDispatcher dispatcher;

    public DispatchEffectHandler(StreamDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String kind() {
        return EffectsQueueDO.KIND_DISPATCH;
    }

    @Override
    public void handle(EffectsQueueDO effect) throws Exception {
        JsonNode payload = JsonMappers.shared().readTree(effect.getPayload());
        long outboxId = payload.path("outboxId").asLong();
        dispatcher.dispatch(outboxId);
    }
}
