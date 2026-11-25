package cn.edu.scut.chatbot.engine.scheduler;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.IdGenerator;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueDO;
import cn.edu.scut.chatbot.engine.effect.EffectsQueueMapper;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.memory.social.GroupStateService;
import cn.edu.scut.chatbot.memory.social.ProactivePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 自主调度器（spec US8）：时钟驱动扫描（到期 Task 的 taskWake 由 task.idx_wake 扫描承担）
 * + 状态驱动扫描（周期扫群静默时长，产生内部触发）。
 *
 * <p>004 T053：静默阈值 / 活跃时段 / 预算 / 间隔全部换 ProactivePolicy 供给
 * （system_param 热变更 + 群体画像活跃时段 + 强负面情绪微调）——静默时长经
 * GroupStateService.read（读取时扣群暂停累计）；间隔拦截由 dedupKey 时间窗承担
 * （窗口 = minInterval，同窗拒插即幂等）；日预算以 trigger_event 事实留档计数
 * （当日 silenceBreak 条数 ≥ 预算即跳过——零新增记账）。
 *
 * <p>内部触发纪律：dedupKey 业务键（scheduled:{类别}:{群}:{时间窗}——同窗口重复触发幂等拒插）、
 * 走与外部触发完全相同的评估管线（L1 批量评估——L0 的 @提及等规则形态不适用于内部触发，
 * 直接入 assessment Effect），不设旁路。
 */
@Component
public class AutonomyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutonomyScheduler.class);

    private final GroupMapper groupMapper;
    private final TriggerEventMapper triggerEventMapper;
    private final EffectsQueueMapper effectsQueueMapper;
    private final GroupStateService groupStateService;
    private final ProactivePolicy proactivePolicy;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AutonomyScheduler(GroupMapper groupMapper,
                             TriggerEventMapper triggerEventMapper,
                             EffectsQueueMapper effectsQueueMapper,
                             GroupStateService groupStateService,
                             ProactivePolicy proactivePolicy,
                             IdGenerator idGenerator,
                             Clock clock) {
        this.groupMapper = groupMapper;
        this.triggerEventMapper = triggerEventMapper;
        this.effectsQueueMapper = effectsQueueMapper;
        this.groupStateService = groupStateService;
        this.proactivePolicy = proactivePolicy;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 状态驱动扫描（60 秒周期）：静默达阈值 + 活跃时段 + 预算未耗尽 → 内部触发
     * （silenceBreak）登记 pending 触发器 + 入 assessment Effect（与外部触发同管线）
     */
    @Scheduled(fixedDelay = 60_000)
    public void scanGroupSilence() {
        List<GroupDO> groups = groupMapper.selectAllInService();
        LocalDateTime now = LocalDateTime.now(clock);
        for (GroupDO group : groups) {
            GroupKey key = new GroupKey(group.getPlatform(), group.getBotId(), group.getGroupId());
            ProactivePolicy.ProactiveParams params = proactivePolicy.effectiveParams(key);
            if (!params.isActiveHour(now)) {
                continue;   // 安静时段（如凌晨）不产生破冰触发
            }
            GroupStateService.GroupStateSnapshot snapshot = groupStateService.read(key);
            if (snapshot.silenceMillis() < params.silenceBreakMillis()) {
                continue;   // 群消息驱动的静默计时未达阈值（读取失败降级空快照同样跳过）
            }
            long todayCount = triggerEventMapper.countInternalByTypeSince(
                    group.getPlatform(), group.getBotId(), group.getGroupId(),
                    "silenceBreak", now.toLocalDate().atStartOfDay());
            if (todayCount >= params.dailyBudget()) {
                continue;   // 当日预算耗尽（成本与群友耐受双上限）
            }
            registerInternalTrigger(group, "silenceBreak",
                    "该群已静默 " + snapshot.silenceMillis() / 60000 + " 分钟",
                    "scheduled:silenceBreak:" + group.getGroupId() + ":"
                            + (clock.millis() / params.minIntervalMillis()));
        }
    }

    /**
     * 内部触发登记：uk_dedup 拒插即同窗口已触发（间隔拦截的幂等形态——窗口 = minInterval）
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
            // 同窗口已触发（间隔拦截——2 小时内第二次被拦）
        }
    }
}
