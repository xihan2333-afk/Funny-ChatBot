package cn.edu.scut.chatbot.engine.turn;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.events.EngineEventPublisher;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.outbox.OutboxMapper;
import cn.edu.scut.chatbot.engine.outbox.OutboxWriter;
import cn.edu.scut.chatbot.exception.CommandRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static cn.edu.scut.chatbot.engine.events.EngineEvent.TurnTransitioned;

/**
 * Turn 协调器（spec US3）：Turn 状态机唯一写入者。单飞排队、重驱 Outbox 查重
 * （崩溃不重复发言）、SETTLING 进入即查已到回执、话题过时放弃。
 *
 * <p>生成驱动经 generation Effect 解耦（领域状态事务内写入、外部动作 Effects 执行——
 * 宪法第三条），本组件的 generationCompleted/generationFailed 由 GenerationRunner 回调。
 */
@Component
public class TurnCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TurnCoordinator.class);

    /**
     * 话题新鲜度窗口：触发器 occurredAt 距今超过该值的 Turn 直接放弃（不补刀）
     */
    private static final Duration TOPIC_FRESHNESS = Duration.ofMinutes(10);

    private final TurnMapper turnMapper;
    private final TriggerEventMapper triggerEventMapper;
    private final OutboxMapper outboxMapper;
    private final OutboxWriter outboxWriter;
    private final EffectsQueueMapper effectsQueueMapper;
    private final IdGenerator idGenerator;
    private final SystemParamService systemParamService;
    private final Clock clock;

    /**
     * 引擎事件发布器（Turn 状态机迁移发射——观测桥的闭合注册表凭 turn:{turnId} 定位
     * 在途 turn Span）。setter 注入可选装配：未装配时零开销，测试构造不受扰。
     */
    private EngineEventPublisher engineEvents;

    @Autowired(required = false)
    void setEngineEventPublisher(EngineEventPublisher publisher) {
        this.engineEvents = publisher;
    }

    public TurnCoordinator(TurnMapper turnMapper,
                           TriggerEventMapper triggerEventMapper,
                           OutboxMapper outboxMapper,
                           OutboxWriter outboxWriter,
                           EffectsQueueMapper effectsQueueMapper,
                           IdGenerator idGenerator,
                           SystemParamService systemParamService,
                           Clock clock) {
        this.turnMapper = turnMapper;
        this.triggerEventMapper = triggerEventMapper;
        this.outboxMapper = outboxMapper;
        this.outboxWriter = outboxWriter;
        this.effectsQueueMapper = effectsQueueMapper;
        this.idGenerator = idGenerator;
        this.systemParamService = systemParamService;
        this.clock = clock;
    }

    private void emitTurnTransitioned(TurnDO turn, String from, String to,
                                      List<String> speechOutboxIds) {
        if (engineEvents != null) {
            engineEvents.publish(new TurnTransitioned(
                    turn.getPlatform(), turn.getBotId(), turn.getGroupId(),
                    turn.getId(), from, to, speechOutboxIds));
        }
    }

    /**
     * 评估接受 → 创建 Turn（请求类：排队超上限可拒）+ 入 generation Effect。
     * 同批合并：同群连续 accept 的触发器并入已在排队的 Turn（单飞的既定语义）。
     *
     * @return turnId
     */
    public long acceptTurn(String platform, String botId, String groupId, long triggerId) {
        int queueMax = systemParamService.getInt("turn.queue_max_per_group", 8);

        // 同批合并：排队中的 Turn 直接并入新触发器（一条消息一句话，不各开各的 Turn）
        List<TurnDO> queued = turnMapper.selectQueuedByGroup(platform, botId, groupId);
        List<TurnDO> active = turnMapper.selectActiveByGroup(platform, botId, groupId);
        if (!queued.isEmpty() && active.isEmpty()) {
            TurnDO merging = queued.get(0);
            appendTrigger(merging, triggerId);
            triggerEventMapper.updateTurnId(triggerId, merging.getId());
            return merging.getId();
        }
        if (queued.size() + active.size() >= queueMax) {
            throw new CommandRejectedException("Turn 排队超上限 " + queueMax
                    + "（群消息过热，稍后再说）");
        }

        long turnId = idGenerator.nextId();
        TurnDO turn = new TurnDO();
        turn.setId(turnId);
        turn.setPlatform(platform);
        turn.setBotId(botId);
        turn.setGroupId(groupId);
        turn.setStatus(TurnStatus.ACCEPTED.name());
        turn.setTriggerIds("[" + triggerId + "]");
        turn.setAcceptedAt(LocalDateTime.now(clock));
        turnMapper.insertTurn(turn);
        triggerEventMapper.updateTurnId(triggerId, turnId);
        emitTurnTransitioned(turn, null, TurnStatus.ACCEPTED.name(), List.of());

        EffectsQueueDO generation = new EffectsQueueDO();
        generation.setPlatform(platform);
        generation.setBotId(botId);
        generation.setGroupId(groupId);
        generation.setKind(EffectsQueueDO.KIND_GENERATION);
        generation.setPayload("{\"form\":\"turn\"}");
        generation.setNextRunAt(LocalDateTime.now(clock));
        effectsQueueMapper.enqueueMerge(generation);
        return turnId;
    }

    /**
     * 生成驱动（generation Effect 的动作体入口；GenerationRunner 完成生成后回调事实方法）。
     * 单飞：群占用中则本 Effect 保持 pending（同 key 合并重投，由 Effects 下轮再试）。
     *
     * @return true = 已驱动（或已恢复推进），false = 群忙稍后重试
     */
    public boolean tryStartGeneration(long turnId) {
        TurnDO turn = turnMapper.selectById(turnId);
        if (turn == null) {
            log.error("generation 载荷指向不存在的 Turn turnId={}", turnId);
            return true;   // 数据不一致：删行不再重试（告警已留）
        }
        if (!TurnStatus.ACCEPTED.name().equals(turn.getStatus())) {
            // 已在推进或已终态（重复认领幂等）
            return true;
        }
        // 单飞：群已有 GENERATING/SETTLING 的 Turn → 稍后重试
        if (!turnMapper.selectActiveByGroup(turn.getPlatform(), turn.getBotId(), turn.getGroupId()).isEmpty()) {
            return false;
        }
        // 话题过时放弃（occurredAt 新鲜度——过时不补刀）
        if (isStale(turn)) {
            dropTurn(turnId, "话题已过时，放弃回应（不补刀）");
            return true;
        }
        // 崩溃恢复查重：重驱前按 turn_id 查 outbox，已有在途出事件跳过重新生成直接结算段
        if (outboxWriter.hasInFlightOutbound(turnId)) {
            log.info("重驱前查得在途出事件，跳过重新生成直接进结算段 turnId={}", turnId);
            int updated = turnMapper.advanceStatus(turnId, TurnStatus.ACCEPTED.name(),
                    TurnStatus.SETTLING.name(), null, null, null);
            if (updated == 0) {
                log.warn("崩溃恢复推进 SETTLING 影响行数为 0 turnId={}", turnId);
            }
            emitTurnTransitioned(turn, TurnStatus.ACCEPTED.name(), TurnStatus.SETTLING.name(), List.of());
            outboxWriter.advanceTurnSettlement(turnId);
            return true;
        }
        // ACCEPTED → GENERATING（DB 条件 UPDATE——并发冲突即影响行数 0，返回已驱动防重复）
        int updated = turnMapper.advanceStatus(turnId, TurnStatus.ACCEPTED.name(),
                TurnStatus.GENERATING.name(), null, null, null);
        if (updated == 0) {
            log.warn("Turn 推进 GENERATING 影响行数为 0（并发推进） turnId={}", turnId);
            return true;
        }
        emitTurnTransitioned(turn, TurnStatus.ACCEPTED.name(), TurnStatus.GENERATING.name(), List.of());
        return true;
    }

    /**
     * 该 Turn 是否就绪可跑（单飞闸的 Effects 侧判定：状态 GENERATING 且群无其他占用）
     */
    public boolean isGeneratingNow(long turnId) {
        TurnDO turn = turnMapper.selectById(turnId);
        return turn != null && TurnStatus.GENERATING.name().equals(turn.getStatus());
    }

    /**
     * 生成完成（事实）：按三结局推进——有出事件 → SETTLING（进入即查已到回执）；
     * 全程无发言 → SILENT 终态；生成失败且重试耗尽 → FAILED
     */
    public void generationCompleted(long turnId, List<Long> speechOutboxIds) {
        TurnDO turn = turnMapper.selectById(turnId);
        if (turn == null || !TurnStatus.GENERATING.name().equals(turn.getStatus())) {
            return;
        }
        if (speechOutboxIds == null || speechOutboxIds.isEmpty()) {
            // 下游否决上游是合法结局：沉默收尾
            turnMapper.advanceStatus(turnId, TurnStatus.GENERATING.name(),
                    TurnStatus.SILENT.name(), LocalDateTime.now(clock), "生成完成全程无发言", null);
            emitTurnTransitioned(turn, TurnStatus.GENERATING.name(), TurnStatus.SILENT.name(), List.of());
            return;
        }
        turnMapper.appendOutboxIds(turnId, speechOutboxIds.stream()
                .map(String::valueOf).reduce("[", (a, b) -> a + b + ",").replaceAll(",$", "]"));
        turnMapper.advanceStatus(turnId, TurnStatus.GENERATING.name(),
                TurnStatus.SETTLING.name(), null, null, null);
        emitTurnTransitioned(turn, TurnStatus.GENERATING.name(), TurnStatus.SETTLING.name(),
                speechOutboxIds.stream().map(String::valueOf).toList());
        // SETTLING 进入即查已到回执（回执早于生成结束到达的既有场景）
        outboxWriter.advanceTurnSettlement(turnId);
    }

    /**
     * 生成失败（事实）：FAILED 终态（错误码留档；崩溃重驱由 Effects 机制处理——
     * generation Effect 行已删，重试语义在 GenerationRunner 内部，耗尽即此方法）
     */
    public void generationFailed(long turnId, String errorCode) {
        TurnDO turn = turnMapper.selectById(turnId);
        int updated = turnMapper.advanceStatus(turnId, TurnStatus.GENERATING.name(),
                TurnStatus.FAILED.name(), LocalDateTime.now(clock), null, errorCode);
        if (updated == 0) {
            log.warn("Turn 推进 FAILED 影响行数为 0 turnId={}", turnId);
        }
        if (turn != null) {
            emitTurnTransitioned(turn, TurnStatus.GENERATING.name(), TurnStatus.FAILED.name(), List.of());
        }
    }

    /**
     * 排队超时放弃（事实）：DROPPED
     */
    public void dropTurn(long turnId, String reason) {
        TurnDO turn = turnMapper.selectById(turnId);
        if (turn == null) {
            return;
        }
        TurnStatus current = TurnStatus.valueOf(turn.getStatus());
        current.assertFactTransition(TurnStatus.DROPPED);
        turnMapper.advanceStatus(turnId, turn.getStatus(), TurnStatus.DROPPED.name(),
                LocalDateTime.now(clock), reason, null);
        emitTurnTransitioned(turn, turn.getStatus(), TurnStatus.DROPPED.name(), List.of());
    }

    /**
     * 排队超时扫描（H2：ACCEPTED 超时置 DROPPED——单飞排队不永久滞留）
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    public void scanQueueTimeout() {
        long timeoutMillis = systemParamService.getLong("turn.queue_timeout_millis", 60000);
        java.time.LocalDateTime deadline = java.time.LocalDateTime.now(clock).minusNanos(timeoutMillis * 1_000_000);
        for (TurnDO turn : turnMapper.selectTimedOutQueued(deadline)) {
            dropTurn(turn.getId(), "排队超时放弃（超过 " + timeoutMillis + "ms 未获得推理权）");
        }
    }

    private boolean isStale(TurnDO turn) {
        List<Long> triggerIds = parseIds(turn.getTriggerIds());
        if (triggerIds.isEmpty()) {
            return false;
        }
        LocalDateTime oldest = null;
        for (Long triggerId : triggerIds) {
            TriggerEventDO trigger = triggerEventMapper.selectById(triggerId);
            if (trigger != null && trigger.getOccurredAt() != null
                    && (oldest == null || trigger.getOccurredAt().isBefore(oldest))) {
                oldest = trigger.getOccurredAt();
            }
        }
        return oldest != null
                && Duration.between(oldest, LocalDateTime.now(clock)).compareTo(TOPIC_FRESHNESS) > 0;
    }

    private void appendTrigger(TurnDO turn, long triggerId) {
        String existing = turn.getTriggerIds();
        String merged = (existing == null || existing.isBlank() || "[]".equals(existing))
                ? "[" + triggerId + "]"
                : existing.replaceAll("]$", "") + "," + triggerId + "]";
        turnMapper.updateTriggerIds(turn.getId(), merged);
    }

    private List<Long> parseIds(String json) {
        try {
            return cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().readValue(
                    json == null ? "[]" : json,
                    cn.edu.scut.chatbot.bootstrap.JsonMappers.shared().getTypeFactory()
                            .constructCollectionType(List.class, Long.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
