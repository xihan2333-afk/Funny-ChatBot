// 守护纪律：错误码只描述系统哪里坏了不承载业务分支；请求拒绝与数据损坏二分（宪法「异常与错误码」节、research R4）
package cn.edu.scut.chatbot

import cn.edu.scut.chatbot.exception.ChatBotException
import cn.edu.scut.chatbot.exception.CommandRejectedException
import cn.edu.scut.chatbot.exception.NonRetryableException
import cn.edu.scut.chatbot.exception.RetryableException
import cn.edu.scut.chatbot.exception.ToolExecutionException
import spock.lang.Specification

class ExceptionSemanticsTest extends Specification {

    def "可重试与不可重试是两个明确语义分支"() {
        expect: "两者都携带点分错误码且继承统一基类"
        def retryable = new RetryableException("engine.stream.io_failed", "Redis 抖动")
        def fatal = new NonRetryableException("engine.turn.state_conflict", "状态矛盾")

        retryable instanceof ChatBotException
        fatal instanceof ChatBotException
        retryable.errorCode == "engine.stream.io_failed"
        fatal.errorCode == "engine.turn.state_conflict"
        !(retryable instanceof NonRetryableException)
        !(fatal instanceof RetryableException)
    }

    def "命令拒绝是合法返回路径 携带reason且不是系统故障"() {
        when:
        def rejected = new CommandRejectedException("Turn 已是终态 SETTLED，拒绝推进 GENERATING")

        then:
        rejected.reason.contains("SETTLED")
        rejected.reason.contains("GENERATING")
        !(rejected instanceof NonRetryableException)
    }

    def "工具执行异常携带tool命名空间错误码"() {
        when:
        def toolFailure = new ToolExecutionException("tool.runCode.timeout", "沙箱调用超时")

        then:
        toolFailure.errorCode == "tool.runCode.timeout"
        toolFailure instanceof ChatBotException
    }
}
