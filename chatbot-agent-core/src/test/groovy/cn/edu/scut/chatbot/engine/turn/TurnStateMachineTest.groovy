// 守护纪律：状态机迁移表集中声明——全部合法对逐条迁移成功、全部非法对拒绝（硬性必测项，4.2.2）
package cn.edu.scut.chatbot.engine.turn

import cn.edu.scut.chatbot.exception.CommandRejectedException
import cn.edu.scut.chatbot.exception.NonRetryableException
import spock.lang.Specification

class TurnStateMachineTest extends Specification {

    def "全部合法对逐条迁移断言通过"() {
        expect:
        TurnStatus.valueOf(from).assertRequestTransition(TurnStatus.valueOf(to))
        TurnStatus.valueOf(from).assertFactTransition(TurnStatus.valueOf(to))

        where:
        from         | to
        'ACCEPTED'   | 'GENERATING'
        'ACCEPTED'   | 'DROPPED'
        'GENERATING' | 'SETTLING'
        'GENERATING' | 'SILENT'
        'GENERATING' | 'FAILED'
        'SETTLING'   | 'SETTLED'
        'SETTLING'   | 'FAILED'
    }

    def "全部非法对请求迁移拒绝抛CommandRejectedException"() {
        when:
        TurnStatus.valueOf(from).assertRequestTransition(TurnStatus.valueOf(to))

        then:
        def e = thrown(CommandRejectedException)
        e.reason.contains(from)
        e.reason.contains(to)

        where:
        from         | to
        'SETTLED'    | 'GENERATING'
        'SILENT'     | 'GENERATING'
        'FAILED'     | 'SETTLING'
        'DROPPED'    | 'ACCEPTED'
        'ACCEPTED'   | 'SETTLED'
        'GENERATING' | 'ACCEPTED'
        'SETTLING'   | 'ACCEPTED'
        'SETTLING'   | 'GENERATING'
    }

    def "全部非法对事实迁移拒绝抛NonRetryableException（数据损坏信号）"() {
        when:
        TurnStatus.valueOf(from).assertFactTransition(TurnStatus.valueOf(to))

        then:
        def e = thrown(NonRetryableException)
        e.errorCode == "engine.turn.state_conflict"

        where:
        from      | to
        'SETTLED' | 'GENERATING'
        'SILENT'  | 'SETTLING'
        'FAILED'  | 'FAILED'
        'DROPPED' | 'SETTLING'
    }

    def "四个终态无出边"() {
        expect:
        TurnStatus.valueOf(state).terminal

        where:
        state << ['SETTLED', 'SILENT', 'FAILED', 'DROPPED']
    }

    def "非终态有出边"() {
        expect:
        !TurnStatus.valueOf(state).terminal

        where:
        state << ['ACCEPTED', 'GENERATING', 'SETTLING']
    }
}
