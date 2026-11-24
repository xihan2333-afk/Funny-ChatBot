package cn.edu.scut.chatbot.engine.scheduler;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 自主调度器（spec US8）：时钟驱动扫描（到期 Task 的 taskWake 由 task.idx_wake 扫描承担）
 * + 状态驱动扫描（周期扫群静默时长，产生内部触发）。
 *
 * <p>内部触发纪律：dedupKey 业务键（scheduled:{类别}:{群}:{时间窗}——同窗口重复触发幂等拒插）、
 * 走与外部触发完全相同的评估管线（L1 批量评估——L0 的 @提及等规则形态不适用于内部触发，
 * 直接入 assessment Effect），不设旁路。
 */
@Component
public class AutonomyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutonomyScheduler.class);

    /**
     * 群静默触发阈值：群两小时无活跃，内部触发一次"打破沉默"评估
     */
    private static final long SILENCE_BREAK_MILLIS = Duration.ofHours(2).toMillis();

    private final GroupMapper groupMapper;
    private final TriggerEventMapper triggerEventMapper;
    private final EffectsQueueMapper effectsQueueMapper;
    private final GroupStateHash groupStateHash;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AutonomyScheduler(GroupMapper groupMapper,
                             TriggerEventMapper triggerEventMapper,
                             EffectsQueueMapper effectsQueueMapper,
                             GroupStateHash groupStateHash,
                             IdGenerator idGenerator,
                             Clock clock) {
        this.groupMapper = groupMapper;
        this.triggerEventMapper = triggerEventMapper;
        this.effectsQueueMapper = effectsQueueMapper;
        this.groupStateHash = groupStateHash;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 状态驱动扫描（60 秒周期）：服务中的群静默超阈值 → 内部触发（silenceBreak）
     * 登记 pending 触发器 + 入 assessment Effect（与外部触发的评估同管线）
     */
    @Scheduled(fixedDelay = 60_000)
    public void scanGroupSilence() {
        List<GroupDO> groups = groupMapper.selectAllInService();
        long now = clock.millis();
        for (GroupDO group : groups) {
            long lastActive = groupStateHash.lastActiveAt(
                    group.getPlatform(), group.getBotId(), group.getGroupId());
            if (lastActive == 0 || now - lastActive < SILENCE_BREAK_MILLIS) {
                continue;
            }
            registerInternalTrigger(group, "silenceBreak",
                    "该群已静默 " + (now - lastActive) / 60000 + " 分钟",
                    "scheduled:silenceBreak:" + group.getGroupId() + ":" + (now / SILENCE_BREAK_MILLIS));
        }
    }

    /**
     * 内部触发登记：uk_dedup 拒插即同窗口已触发（幂等）
     */
    private void registerInternalTrigger(GroupDO group, String eventType, String digest, String dedupKey) {
        TriggerEventDO trigger = new TriggerEventDO();
        trigger.setId(idGenerator.nextId());
        trigger.setPlatform(group.getPlatform());
        trigger.setBotId(group.getBotId());
        trigger.setGroupId(group.getGroupId());
        trigger.setTriggerKind(TriggerEventDO.KIND_INTERNAL);
        trigger.setEventType(eventType);
        trigger.setDedupKey(dedupKey);
        trigger.setDigest(digest);
        trigger.setDecision(TriggerEventDO.DECISION_PENDING);
        trigger.setOccurredAt(LocalDateTime.now(clock));
        try {
            triggerEventMapper.insertTrigger(trigger);
            log.info("内部触发登记（走同一套评估管线） group={} type={}", group.getGroupId(), eventType);
            EffectsQueueDO assessment = new EffectsQueueDO();
            assessment.setPlatform(group.getPlatform());
            assessment.setBotId(group.getBotId());
            assessment.setGroupId(group.getGroupId());
            assessment.setKind(EffectsQueueDO.KIND_ASSESSMENT);
            assessment.setPayload("{}");
            assessment.setNextRunAt(LocalDateTime.now(clock));
            effectsQueueMapper.enqueueMerge(assessment);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 同窗口已触发（幂等）
        }
    }
}
