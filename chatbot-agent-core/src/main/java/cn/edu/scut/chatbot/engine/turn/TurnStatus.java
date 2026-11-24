package cn.edu.scut.chatbot.engine.turn;

import cn.edu.scut.chatbot.exception.CommandRejectedException;
import cn.edu.scut.chatbot.exception.NonRetryableException;
import cn.edu.scut.chatbot.engine.EngineErrorCodes;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Turn 状态机（data-model 第 7 节、research R4）：枚举 + EnumMap 不可变迁移表，
 * 全部「合法对」与「非法对拒绝」是硬性必测项。
 *
 * <p>非法迁移二分（research R4）：
 * 请求类冲突（如对 SETTLED 的 Turn 请求推进）抛 {@link CommandRejectedException}——
 * 请求可被拒绝是合法路径；事实与持久态矛盾（DB 里 SETTLED 却收到推进事实）抛
 * {@link NonRetryableException}（engine.* 错误码）+ 告警——数据损坏信号。
 */
public enum TurnStatus {

    ACCEPTED,
    GENERATING,
    SETTLING,
    SETTLED,
    SILENT,
    FAILED,
    DROPPED;

    /**
     * 唯一合法迁移集（data-model 第 7 节迁移表逐条誊写；终态无出边）
     */
    private static final Map<TurnStatus, Set<TurnStatus>> TRANSITIONS = buildTransitions();

    private static Map<TurnStatus, Set<TurnStatus>> buildTransitions() {
        Map<TurnStatus, Set<TurnStatus>> table = new EnumMap<>(TurnStatus.class);
        table.put(ACCEPTED, Set.of(GENERATING, DROPPED));
        table.put(GENERATING, Set.of(SETTLING, SILENT, FAILED));
        table.put(SETTLING, Set.of(SETTLED, FAILED));
        table.put(SETTLED, Collections.emptySet());
        table.put(SILENT, Collections.emptySet());
        table.put(FAILED, Collections.emptySet());
        table.put(DROPPED, Collections.emptySet());
        return Collections.unmodifiableMap(table);
    }

    /**
     * 终态判定（SETTLED / SILENT / FAILED / DROPPED 不可再迁移）
     */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    /**
     * 请求类迁移断言：非法目标抛 CommandRejectedException（携带当前态与请求态）
     */
    public void assertRequestTransition(TurnStatus target) {
        if (!TRANSITIONS.get(this).contains(target)) {
            throw new CommandRejectedException(
                    "Turn 非法迁移请求：当前态 " + this + " → 请求态 " + target
                            + "（合法目标：" + TRANSITIONS.get(this) + "）");
        }
    }

    /**
     * 事实类迁移断言：持久态与事实矛盾抛 NonRetryableException（数据损坏信号）
     */
    public void assertFactTransition(TurnStatus target) {
        if (!TRANSITIONS.get(this).contains(target)) {
            throw new NonRetryableException(EngineErrorCodes.TURN_STATE_CONFLICT,
                    "Turn 状态与推进事实矛盾：持久态 " + this + " 收到事实 " + target
                            + "（合法目标：" + TRANSITIONS.get(this) + "）——数据损坏信号");
        }
    }

    /**
     * 迁移表视图（测试枚举全部合法对/非法对用）
     */
    public static Map<TurnStatus, Set<TurnStatus>> transitions() {
        return TRANSITIONS;
    }
}
