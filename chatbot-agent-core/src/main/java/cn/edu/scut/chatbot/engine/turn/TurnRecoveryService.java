package cn.edu.scut.chatbot.engine.turn;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Turn 崩溃恢复（Code Review C2）：GENERATING 态 Turn 在进程崩溃后（generation Effect 行
 * 已随消费删除）若无恢复路径会永久滞留并锁死该群单飞。启动时扫描非终态 Turn：
 * 有在途出事件 → 推 SETTLING + 结算；无 → 重入 generation Effect 重驱。
 */
@Component
public class TurnRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TurnRecoveryService.class);

    private final TurnMapper turnMapper;
    private final OutboxWriter outboxWriter;
    private final EffectsQueueMapper effectsQueueMapper;
    private final Clock clock;

    public TurnRecoveryService(TurnMapper turnMapper, OutboxWriter outboxWriter,
                               EffectsQueueMapper effectsQueueMapper, Clock clock) {
        this.turnMapper = turnMapper;
        this.outboxWriter = outboxWriter;
        this.effectsQueueMapper = effectsQueueMapper;
        this.clock = clock;
    }

    @PostConstruct
    public void recoverNonTerminalTurns() {
        int requeued = 0;
        for (TurnDO turn : turnMapper.selectNonTerminalAll()) {
            String status = turn.getStatus();
            if ("SETTLING".equals(status)) {
                outboxWriter.advanceTurnSettlement(turn.getId());
            } else if ("GENERATING".equals(status)) {
                if (outboxWriter.hasInFlightOutbound(turn.getId())) {
                    turnMapper.advanceStatus(turn.getId(), "GENERATING", "SETTLING", null, null, null);
                    outboxWriter.advanceTurnSettlement(turn.getId());
                } else {
                    reenqueueGeneration(turn);
                    requeued++;
                }
            } else if ("ACCEPTED".equals(status)) {
                reenqueueGeneration(turn);
                requeued++;
            }
        }
        if (requeued > 0) {
            log.info("Turn 启动恢复：{} 条非终态 Turn 重入 generation Effect", requeued);
        }
    }

    private void reenqueueGeneration(TurnDO turn) {
        EffectsQueueDO effect = new EffectsQueueDO();
        effect.setPlatform(turn.getPlatform());
        effect.setBotId(turn.getBotId());
        effect.setGroupId(turn.getGroupId());
        effect.setKind(EffectsQueueDO.KIND_GENERATION);
        effect.setPayload("{\"form\":\"turn\"}");
        effect.setNextRunAt(LocalDateTime.now(clock));
        effectsQueueMapper.enqueueMerge(effect);
    }
}
