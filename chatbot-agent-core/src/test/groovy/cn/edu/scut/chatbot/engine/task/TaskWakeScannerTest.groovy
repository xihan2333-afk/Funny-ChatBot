// 守护纪律：WAITING 任务两条推进线——条件唤醒（求值命中才推进）与超期终结（过期不再等）；
// 坏条件不唤醒也不报错（设计 :452/:458）
package cn.edu.scut.chatbot.engine.task

import cn.edu.scut.chatbot.engine.intake.TriggerEventMapper
import cn.edu.scut.chatbot.fixtures.FixedClock
import spock.lang.Specification

import java.time.LocalDateTime

class TaskWakeScannerTest extends Specification {

    def taskMapper = Mock(TaskMapper)
    def taskCoordinator = Mock(TaskCoordinator)
    def triggerEventMapper = Mock(TriggerEventMapper)

    /** 固定时钟：扫描器眼中的「现在」恒为 2026-05-01 08:00（Asia/Shanghai） */
    FixedClock clock = new FixedClock()

    TaskWakeScanner scanner = new TaskWakeScanner(taskMapper, taskCoordinator, triggerEventMapper, clock)

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 1, 8, 0)

    def "groupActive条件 近10分钟有新触发器即唤醒命中"() {
        given: "该群最近一条触发器在 5 分钟前（07:55，10 分钟窗口内）"
        taskMapper.selectWaiting() >> [waitingTask(11L, '{"kind":"groupActive"}', null)]
        triggerEventMapper.selectLastOccurredAt(*_) >> LocalDateTime.of(2026, 5, 1, 7, 55)

        when:
        scanner.scanWaitingTasks()

        then: "命中唤醒——WAITING 任务推进交生成"
        1 * taskCoordinator.advanceTask(11L)
    }

    def "groupActive条件 最近触发器早于10分钟窗口或无触发器不命中"() {
        given:
        taskMapper.selectWaiting() >> [waitingTask(11L, '{"kind":"groupActive"}', null)]
        triggerEventMapper.selectLastOccurredAt(*_) >> lastTriggerAt

        when:
        scanner.scanWaitingTasks()

        then:
        0 * taskCoordinator.advanceTask(_)

        where: "07:45（15 分钟前）已出窗口；null = 从未有触发器"
        lastTriggerAt << [LocalDateTime.of(2026, 5, 1, 7, 45), null]
    }

    def "groupQuiet条件 静默满N分钟命中"() {
        given: "minutes=30：最近触发器在 45 分钟前（07:15，已静默 45 分钟 ≥ 30）"
        taskMapper.selectWaiting() >> [waitingTask(12L, '{"kind":"groupQuiet","minutes":30}', null)]
        triggerEventMapper.selectLastOccurredAt(*_) >> LocalDateTime.of(2026, 5, 1, 7, 15)

        when:
        scanner.scanWaitingTasks()

        then:
        1 * taskCoordinator.advanceTask(12L)
    }

    def "groupQuiet条件 静默不足N分钟不命中（参数N参与判定）"() {
        given: "minutes=30 但最近触发器在 10 分钟前（07:50，静默不足）"
        taskMapper.selectWaiting() >> [waitingTask(12L, '{"kind":"groupQuiet","minutes":30}', null)]
        triggerEventMapper.selectLastOccurredAt(*_) >> LocalDateTime.of(2026, 5, 1, 7, 50)

        when:
        scanner.scanWaitingTasks()

        then:
        0 * taskCoordinator.advanceTask(_)
    }

    def "groupQuiet条件 无任何触发器视为已静默（空群也算静默满）"() {
        given:
        taskMapper.selectWaiting() >> [waitingTask(13L, '{"kind":"groupQuiet","minutes":30}', null)]
        triggerEventMapper.selectLastOccurredAt(*_) >> null

        when:
        scanner.scanWaitingTasks()

        then:
        1 * taskCoordinator.advanceTask(13L)
    }

    def "坏JSON与未知条件按不命中处理（不唤醒也不报错）"() {
        given: "群内 1 分钟前有新触发器——若条件被误判为命中就会推进（负向放大器）"
        taskMapper.selectWaiting() >> [waitingTask(11L, wakeCondition, null)]
        triggerEventMapper.selectLastOccurredAt(*_) >> LocalDateTime.of(2026, 5, 1, 7, 59)

        when:
        scanner.scanWaitingTasks()

        then:
        0 * taskCoordinator.advanceTask(_)
        notThrown(Exception)

        where:
        wakeCondition << ['这不是JSON{{{', '{"kind":"unknownKind"}', '   ', null]
    }

    def "超期任务置EXPIRED终态留痕 不再参与条件唤醒"() {
        given: "expireAt 已过 5 分钟（07:55），且 wakeCondition 本会命中（groupActive 窗口内有触发器）"
        taskMapper.selectWaiting() >> [waitingTask(14L, '{"kind":"groupActive"}', LocalDateTime.of(2026, 5, 1, 7, 55))]
        triggerEventMapper.selectLastOccurredAt(*_) >> LocalDateTime.of(2026, 5, 1, 7, 59)

        when:
        scanner.scanWaitingTasks()

        then: "过期优先：安静终结留痕，不走唤醒推进（响应挂在 interaction 上——given stub 会被同名 interaction 的空响应覆盖）"
        1 * taskMapper.advanceStatus(14L, TaskStatus.WAITING.name(), TaskStatus.EXPIRED.name()) >> 1
        1 * taskMapper.updateResult(14L, null, "engine.task.expired", _)
        1 * taskMapper.insertExecutionLog({ TaskExecutionLogDO logEntry ->
            logEntry.taskId == 14L && logEntry.note.contains("超期终结")
        })
        0 * taskCoordinator.advanceTask(_)
    }

    def "expireAt未到或为空不触发超期终结"() {
        given: "expireAt 在 10 分钟后（08:10）或为空；无触发器数据——隔离两条推进线"
        taskMapper.selectWaiting() >> [waitingTask(15L, "", expireAt)]
        triggerEventMapper.selectLastOccurredAt(*_) >> null

        when:
        scanner.scanWaitingTasks()

        then:
        0 * taskMapper.advanceStatus(_, _, _)
        0 * taskMapper.updateResult(*_)
        0 * taskMapper.insertExecutionLog(_)

        where:
        expireAt << [LocalDateTime.of(2026, 5, 1, 8, 10), null]
    }

    def "expireAt恰为当前时刻不算过期（isBefore 严格早于）"() {
        given:
        taskMapper.selectWaiting() >> [waitingTask(16L, null, NOW)]
        triggerEventMapper.selectLastOccurredAt(*_) >> null

        when:
        scanner.scanWaitingTasks()

        then:
        0 * taskMapper.advanceStatus(_, _, _)
    }

    // ===== 构造辅助 =====

    /** WAITING 任务（platform/bot/group 固定三元组；wakeCondition 与 expireAt 可空） */
    private TaskDO waitingTask(long id, String wakeCondition, LocalDateTime expireAt) {
        TaskDO task = new TaskDO()
        task.setId(id)
        task.setPlatform("qq")
        task.setBotId("10000")
        task.setGroupId("80001")
        task.setStatus(TaskStatus.WAITING.name())
        task.setWakeCondition(wakeCondition)
        task.setExpireAt(expireAt)
        return task
    }
}
