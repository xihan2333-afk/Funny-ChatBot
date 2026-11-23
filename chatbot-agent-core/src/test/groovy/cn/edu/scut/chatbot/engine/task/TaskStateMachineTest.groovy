// 守护纪律：状态机迁移表集中声明——全部合法对逐条迁移成功、全部非法对拒绝（硬性必测项）
package cn.edu.scut.chatbot.engine.task

import cn.edu.scut.chatbot.exception.CommandRejectedException
import cn.edu.scut.chatbot.exception.NonRetryableException
import spock.lang.Specification

class TaskStateMachineTest extends Specification {

    def "全部合法对逐条迁移断言通过"() {
        expect:
        TaskStatus.valueOf(from).assertRequestTransition(TaskStatus.valueOf(to))
        TaskStatus.valueOf(from).assertFactTransition(TaskStatus.valueOf(to))

        where:
        from        | to
        'SCHEDULED' | 'RUNNING'
        'SCHEDULED' | 'CANCELLED'
        'RUNNING'   | 'WAITING'
        'RUNNING'   | 'DONE'
        'RUNNING'   | 'FAILED'
        'RUNNING'   | 'CANCELLED'
        'WAITING'   | 'RUNNING'
        'WAITING'   | 'CANCELLED'
        'WAITING'   | 'EXPIRED'
    }

    def "全部非法对请求迁移拒绝"() {
        when:
        TaskStatus.valueOf(from).assertRequestTransition(TaskStatus.valueOf(to))

        then:
        thrown(CommandRejectedException)

        where:
        from        | to
        'DONE'      | 'RUNNING'
        'FAILED'    | 'RUNNING'
        'CANCELLED' | 'RUNNING'
        'EXPIRED'   | 'WAITING'
        'SCHEDULED' | 'DONE'
        'SCHEDULED' | 'WAITING'
        'RUNNING'   | 'SCHEDULED'
        'WAITING'   | 'DONE'
    }

    def "非法事实迁移抛NonRetryableException"() {
        when:
        TaskStatus.valueOf('DONE').assertFactTransition(TaskStatus.valueOf('RUNNING'))

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == "engine.task.state_conflict"
    }

    def "四个终态无出边"() {
        expect:
        TaskStatus.valueOf(state).terminal

        where:
        state << ['DONE', 'FAILED', 'CANCELLED', 'EXPIRED']
    }
}
