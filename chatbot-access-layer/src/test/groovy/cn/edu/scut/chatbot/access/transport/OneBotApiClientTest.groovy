package cn.edu.scut.chatbot.access.transport

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * 守护纪律：API 调用补齐投递侧语义（契约 onebot-ws-adapter.md 第 2/3 节 + T036）。
 * 连接级失败静默重试一次（全局口径）；retcode 明确失败不重试（重试只会重复副作用）；
 * 就绪门控——就绪前的调用等待不失败。单元测试 Mock 一切协作者（宪法第七条），
 * 异步断言用 CompletableFuture 的限时 get（确定性完成，不用轮询等待）。
 */
class OneBotApiClientTest extends Specification {

    def wsClient = Mock(OneBotWsClient)
    def apiClient = new OneBotApiClient(wsClient)
    def mapper = new ObjectMapper()

    private def responseFrame(int retcode, String dataJson = '{}') {
        mapper.readTree('{"status":"ok","retcode":' + retcode + ',"data":' + dataJson + ',"echo":"echo-1"}')
    }

    def "连接就绪时调用直达且响应帧映射为结构化视图"() {
        given:
        wsClient.isReady() >> true
        wsClient.call("get_login_info", '{}') >> CompletableFuture.completedFuture(
                responseFrame(0, '{"user_id":10000,"nickname":"bot"}'))

        when:
        def response = apiClient.call("get_login_info", '{}').get(2, TimeUnit.SECONDS)

        then:
        response.retcode() == 0
        response.isOk()
        response.data().path("user_id").asLong() == 10000L
        response.echo() == "echo-1"
        response.raw().contains('"retcode":0')
    }

    def "retcode一表示已提交异步同样算调用成功"() {
        given:
        wsClient.isReady() >> true
        wsClient.call(_, _) >> CompletableFuture.completedFuture(responseFrame(1))

        expect:
        apiClient.call("send_group_msg", '{}').get(2, TimeUnit.SECONDS).isOk()
    }

    def "retcode明确失败时响应正常返回给调用方判定不重试"() {
        given: "平台语义已拒绝（如 1403 token 失败）"
        wsClient.isReady() >> true

        when:
        def response = apiClient.call("send_group_msg", '{}').get(2, TimeUnit.SECONDS)

        then:
        "future 正常完成（调用到达了平台），失败分类由调用方做——重试只会重复副作用。" +
                "断言自带响应——then 裸断言会遮蔽 given 的 stub"
        1 * wsClient.call(_, _) >> CompletableFuture.completedFuture(responseFrame(1403))
        !response.isOk()
        response.retcode() == 1403
    }

    def "连接级失败静默重试一次且重试成功"() {
        given: "第一次调用时连接已断（IllegalStateException），重连后重发成功"
        wsClient.isReady() >> true

        when:
        def response = apiClient.call("send_group_msg", '{}').get(2, TimeUnit.SECONDS)

        then: "两次调用：首发失败 + 静默重试一次成功"
        2 * wsClient.call(_, _) >>> [
                CompletableFuture.failedFuture(new IllegalStateException("协议端连接未建立")),
                CompletableFuture.completedFuture(responseFrame(0, '{"message_id":7654321}'))
        ]
        response.isOk()
    }

    def "连接级失败重试一次仍失败则异常完成不再第三次调用"() {
        given:
        wsClient.isReady() >> true

        when:
        apiClient.call("send_group_msg", '{}').get(2, TimeUnit.SECONDS)

        then: "两次调用后放弃——重试上限一次，不会第三次"
        2 * wsClient.call(_, _) >> CompletableFuture.failedFuture(new IllegalStateException("协议端连接未建立"))
        def e = thrown(ExecutionException)
        e.cause instanceof IllegalStateException
    }

    def "非连接级失败不重试直接异常完成"() {
        given: "参数序列化缺陷之类的程序性异常——重试没有意义"
        wsClient.isReady() >> true
        wsClient.call(_, _) >> CompletableFuture.failedFuture(new IllegalArgumentException("params 非法"))

        when:
        apiClient.call("send_group_msg", '{}').get(2, TimeUnit.SECONDS)

        then:
        thrown(ExecutionException)
        1 * wsClient.call(_, _)
    }

    def "连接未就绪时调用等待就绪不立即失败"() {
        given: "第一次查就绪为 false，等待轮询期间变为 true（就绪门控：等待不失败）"
        wsClient.isRunning() >> true
        wsClient.isReady() >>> [false, true]
        wsClient.call(_, _) >> CompletableFuture.completedFuture(responseFrame(0))

        when:
        def response = apiClient.call("get_version_info", '{}').get(3, TimeUnit.SECONDS)

        then:
        response.isOk()
    }
}
