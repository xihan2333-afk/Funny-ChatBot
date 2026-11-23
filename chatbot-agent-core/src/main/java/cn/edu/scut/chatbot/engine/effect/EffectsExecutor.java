package cn.edu.scut.chatbot.engine.effect;

import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.bootstrap.GroupSerialExecutor;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Effects 队列执行器（spec US3、设计方案 3.4）：500ms @Scheduled 扫描 due 行、乐观抢锁
 * （条件 UPDATE 置 running）、执行经 GroupSerialExecutor 同群保序、五 kind 处理器注册表、
 * 失败指数退避（2^n 秒封顶 10 分钟、超 5 次置 failed 落告警）、启动恢复 running 批量置回
 * pending（崩溃动作不丢是结构保证）。
 */
@Component
public class EffectsExecutor {

    private static final Logger log = LoggerFactory.getLogger(EffectsExecutor.class);

    private final EffectsQueueMapper mapper;
    private final GroupSerialExecutor serialExecutor;
    private final SystemParamService systemParamService;
    private final Clock clock;
    private final long backoffBaseSeconds;
    private final long backoffMaxSeconds;
    private final int maxRetryCount;
    private final int scanBatchSize;

    /**
     * kind → 处理器（注册表：启动收集全部 EffectHandler bean）
     */
    private final Map<String, EffectHandler> handlers = new HashMap<>();

    public EffectsExecutor(EffectsQueueMapper mapper,
                           GroupSerialExecutor serialExecutor,
                           List<EffectHandler> handlerBeans,
                           SystemParamService systemParamService,
                           Clock clock,
                           @Value("${agent.effect.backoff-base-seconds:2}") long backoffBaseSeconds,
                           @Value("${agent.effect.backoff-max-seconds:600}") long backoffMaxSeconds,
                           @Value("${agent.effect.max-retry-count:5}") int maxRetryCount,
                           @Value("${agent.effect.scan-batch-size:32}") int scanBatchSize) {
        this.mapper = mapper;
        this.serialExecutor = serialExecutor;
        this.systemParamService = systemParamService;
        this.clock = clock;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.backoffMaxSeconds = backoffMaxSeconds;
        this.maxRetryCount = maxRetryCount;
        this.scanBatchSize = scanBatchSize;
        for (EffectHandler handler : handlerBeans) {
            handlers.put(handler.kind(), handler);
        }
    }

    /**
     * 启动恢复：running 行批量置回 pending（崩溃动作不丢——行未删即动作未完成）
     */
    @PostConstruct
    public void recoverOnStartup() {
        try {
            int recovered = mapper.recoverRunningToPending();
            if (recovered > 0) {
                log.info("启动恢复：{} 条 running Effect 置回 pending 续跑", recovered);
            }
        } catch (Exception e) {
            log.error("启动恢复失败 errorCode=" + EffectErrorCodes.RECOVERY_FAILED, e);
        }
    }

    /**
     * 扫描循环（500ms）：due 行 → 抢锁 → 经群串行泵执行（同群保序）
     */
    @Scheduled(fixedDelayString = "${agent.effect.scan-interval-millis:500}")
    public void scanAndExecute() {
        List<EffectsQueueDO> due;
        try {
            due = mapper.selectDue(LocalDateTime.now(clock), scanBatchSize);
        } catch (Exception e) {
            log.warn("Effect 扫描查询失败（下轮再试）", e);
            return;
        }
        for (EffectsQueueDO effect : due) {
            LocalDateTime now = LocalDateTime.now(clock);
            int claimed = mapper.claimForRun(effect.getId(), now);
            if (claimed == 0) {
                continue;   // 并发抢锁失败：其他执行器已领走
            }
            // 执行经 GroupSerialExecutor 同群保序（effect 的 next_run_at 顺序即执行顺序）
            serialExecutor.submit(effect.getBotId(), effect.getGroupId(),
                    () -> executeClaimed(effect));
        }
    }

    private void executeClaimed(EffectsQueueDO effect) {
        EffectHandler handler = handlers.get(effect.getKind());
        if (handler == null) {
            log.error("未知 Effect kind（置 failed） errorCode={} kind={}",
                    EffectErrorCodes.UNKNOWN_KIND, effect.getKind());
            mapper.markFailed(effect.getId());
            return;
        }
        try {
            handler.handle(effect);
            // 成功删行（队列不是日志）
            mapper.deleteById(effect.getId());
        } catch (NonRetryableException e) {
            log.error("Effect 不可重试失败（置 failed） errorCode={} effectId={} kind={}",
                    e.getErrorCode(), effect.getId(), effect.getKind(), e);
            mapper.markFailed(effect.getId());
        } catch (Exception e) {
            // 可重试失败：指数退避 2^n 秒封顶 10 分钟；超 5 次置 failed 落告警
            int nextRetry = (effect.getRetryCount() == null ? 0 : effect.getRetryCount()) + 1;
            if (nextRetry > maxRetryCount) {
                log.error("Effect 重试超限置 failed errorCode={} effectId={} kind={} retries={}",
                        EffectErrorCodes.RETRY_EXCEEDED, effect.getId(), effect.getKind(), nextRetry, e);
                mapper.markFailed(effect.getId());
                return;
            }
            long backoffSeconds = Math.min(backoffBaseSeconds * (1L << (nextRetry - 1)), backoffMaxSeconds);
            mapper.scheduleRetry(effect.getId(), nextRetry,
                    LocalDateTime.now(clock).plusSeconds(backoffSeconds));
            log.warn("Effect 可重试失败退避（第 {} 次，等 {}s 后重试） effectId={} kind={}",
                    nextRetry, backoffSeconds, effect.getId(), effect.getKind(), e);
        }
    }
}
