package cn.edu.scut.chatbot.engine.task;

import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.engine.EngineErrorCodes;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Task 状态机（data-model 第 8 节迁移表、research R4）：七态 + EnumMap 不可变迁移表。
 * 全部合法对与非法对拒绝是硬性必测项。
 */
public enum TaskStatus {

    SCHEDULED,
    RUNNING,
    WAITING,
    DONE,
    FAILED,
    CANCELLED,
    EXPIRED;

    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = buildTransitions();

    private static Map<TaskStatus, Set<TaskStatus>> buildTransitions() {
        Map<TaskStatus, Set<TaskStatus>> table = new EnumMap<>(TaskStatus.class);
        table.put(SCHEDULED, Set.of(RUNNING, CANCELLED));
        table.put(RUNNING, Set.of(WAITING, DONE, FAILED, CANCELLED));
        table.put(WAITING, Set.of(RUNNING, CANCELLED, EXPIRED));
        table.put(DONE, Collections.emptySet());
        table.put(FAILED, Collections.emptySet());
        table.put(CANCELLED, Collections.emptySet());
        table.put(EXPIRED, Collections.emptySet());
        return Collections.unmodifiableMap(table);
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    public void assertRequestTransition(TaskStatus target) {
        if (!TRANSITIONS.get(this).contains(target)) {
            throw new CommandRejectedException(
                    "Task 非法迁移请求：当前态 " + this + " → 请求态 " + target
                            + "（合法目标：" + TRANSITIONS.get(this) + "）");
        }
    }

    public void assertFactTransition(TaskStatus target) {
        if (!TRANSITIONS.get(this).contains(target)) {
            throw new NonRetryableException(EngineErrorCodes.TASK_STATE_CONFLICT,
                    "Task 状态与推进事实矛盾：持久态 " + this + " 收到事实 " + target
                            + "——数据损坏信号");
        }
    }

    public static Map<TaskStatus, Set<TaskStatus>> transitions() {
        return TRANSITIONS;
    }
}
