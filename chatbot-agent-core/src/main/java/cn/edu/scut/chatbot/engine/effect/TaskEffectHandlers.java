package cn.edu.scut.chatbot.engine.effect;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.engine.task.TaskCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * taskWake 处理器（US4 兑现——原 Phase 5 占位替换）：到期/条件唤醒推进 Task。
 * workerLaunch 由 WorkerLauncher 自实现 EffectHandler（本文件不再占位）。
 */
public final class TaskEffectHandlers {

    private static final Logger log = LoggerFactory.getLogger(TaskEffectHandlers.class);

    private TaskEffectHandlers() {
    }

    @Component
    static class TaskWakeHandler implements EffectHandler {

        private final TaskCoordinator taskCoordinator;

        TaskWakeHandler(TaskCoordinator taskCoordinator) {
            this.taskCoordinator = taskCoordinator;
        }

        @Override
        public String kind() {
            return EffectsQueueDO.KIND_TASK_WAKE;
        }

        @Override
        public void handle(EffectsQueueDO effect) throws Exception {
            JsonNode payload = JsonMappers.shared().readTree(effect.getPayload());
            long taskId = payload.path("taskId").asLong();
            taskCoordinator.advanceTask(taskId);
        }
    }
}
