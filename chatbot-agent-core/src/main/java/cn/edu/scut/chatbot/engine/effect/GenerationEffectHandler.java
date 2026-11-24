package cn.edu.scut.chatbot.engine.effect;

import cn.edu.scut.chatbot.exception.RetryableException;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.engine.task.TaskMapper;
import cn.edu.scut.chatbot.engine.turn.GenerationRunner;
import cn.edu.scut.chatbot.engine.turn.TurnCoordinator;
import cn.edu.scut.chatbot.engine.turn.TurnDO;
import cn.edu.scut.chatbot.engine.turn.TurnMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * generation 处理器（Code Review H1 指针语义）：uk_merge 同群同 kind 只有一行 Effect，
 * 载荷带具体对象 ID 会被后续入队覆盖导致驱动丢失——改为指针驱动：执行时按优先级取该群
 * 最旧待驱动对象（排队 Turn → 轻任务 → 结论交付），行只是"该群有生成动作待做"的信号。
 */
@Component
public class GenerationEffectHandler implements EffectHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerationEffectHandler.class);

    private final TurnCoordinator turnCoordinator;
    private final GenerationRunner generationRunner;
    private final TurnMapper turnMapper;
    private final TaskMapper taskMapper;
    private final TriggerEventMapper triggerEventMapper;

    public GenerationEffectHandler(TurnCoordinator turnCoordinator,
                                   GenerationRunner generationRunner,
                                   TurnMapper turnMapper,
                                   TaskMapper taskMapper,
                                   TriggerEventMapper triggerEventMapper) {
        this.turnCoordinator = turnCoordinator;
        this.generationRunner = generationRunner;
        this.turnMapper = turnMapper;
        this.taskMapper = taskMapper;
        this.triggerEventMapper = triggerEventMapper;
    }

    @Override
    public String kind() {
        return EffectsQueueDO.KIND_GENERATION;
    }

    @Override
    public void handle(EffectsQueueDO effect) {
        String platform = effect.getPlatform();
        String botId = effect.getBotId();
        String groupId = effect.getGroupId();

        // 1. 最旧排队 Turn（单飞闸：群忙抛可重试让行保持 pending 下轮再试）
        List<TurnDO> queued = turnMapper.selectQueuedByGroup(platform, botId, groupId);
        if (!queued.isEmpty()) {
            long turnId = queued.get(0).getId();
            boolean ready = turnCoordinator.tryStartGeneration(turnId);
            if (!ready) {
                throw new RetryableException("effect.generation.group_busy", "群推理占用中，稍后重试");
            }
            if (turnCoordinator.isGeneratingNow(turnId)) {
                runAsync("turn-run-" + turnId, () -> {
                    try {
                        generationRunner.runTurn(turnId);
                    } catch (Exception e) {
                        log.error("Run 驱动异常 turnId={}（Turn 置 FAILED）", turnId, e);
                        turnCoordinator.generationFailed(turnId, "engine.turn.generation_failed:run_crashed");
                    }
                });
            }
            return;
        }

        // 2. 最旧 RUNNING 的轻任务
        TaskDO lightTask = taskMapper.selectOldestRunningLightByGroup(platform, botId, groupId);
        if (lightTask != null) {
            long taskId = lightTask.getId();
            runAsync("task-gen-" + taskId, () -> {
                try {
                    generationRunner.runLightTask(taskId);
                } catch (Exception e) {
                    // M4：轻任务生成路径崩溃也要推进状态（不留悬挂 RUNNING）
                    log.error("轻任务生成路径异常 taskId={}", taskId, e);
                    try {
                        cn.edu.scut.chatbot.engine.task.TaskCoordinator coordinator =
                                taskCoordinatorRef;
                        if (coordinator != null) {
                            coordinator.taskConcluded(taskId, null,
                                    "engine.task.light_failed:generation_crashed");
                        }
                    } catch (Exception inner) {
                        log.error("轻任务失败结论回传也失败 taskId={}", taskId, inner);
                    }
                }
            });
            return;
        }

        // 3. 最旧未交付的任务结论事件（交付后回填 turn_id=-1 标记）
        TriggerEventDO delivery = triggerEventMapper.selectOldestUndeliveredTaskDelivery(
                platform, botId, groupId);
        if (delivery != null && delivery.getSourceRef() != null) {
            long taskId;
            try {
                taskId = Long.parseLong(delivery.getSourceRef());
            } catch (NumberFormatException e) {
                triggerEventMapper.updateTurnId(delivery.getId(), -1L);
                return;
            }
            runAsync("task-delivery-" + taskId, () -> {
                try {
                    generationRunner.runDelivery(taskId);
                } catch (Exception e) {
                    log.error("结论交付路径异常 taskId={}", taskId, e);
                } finally {
                    // 标记已处理（无论成败——失败靠 Task 侧结论留痕，不无限重投）
                    triggerEventMapper.updateTurnId(delivery.getId(), -1L);
                }
            });
        }
        // 4. 都无：无事可做（Effect 行由执行器成功删行）
    }

    private void runAsync(String name, Runnable action) {
        Thread.ofVirtual().name(name).start(action);
    }

    /**
     * TaskCoordinator 引用（M4 修复用；构造器循环依赖规避——字段注入）
     */
    @org.springframework.beans.factory.annotation.Autowired
    private cn.edu.scut.chatbot.engine.task.TaskCoordinator taskCoordinatorRef;
}
