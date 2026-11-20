package cn.edu.scut.chatbot.access.transport

import spock.lang.Specification

import java.lang.reflect.Field
import java.net.http.WebSocket
import java.time.Clock
import java.util.concurrent.CountDownLatch

/**
 * 守护纪律：判死 / ping 失败的主动断开必须显式放行重连信号（回归防护）。
 * JDK WebSocket 的 abort() 不保证触发 Listener 的关闭回调（连接僵死时回调可能永远不来），
 * 而重连循环等待的正是这个信号——只 abort 不倒数会让循环永久挂起（静默时长持续增长、
 * 再无任何重连，集成测试实测踩坑的根因）。就绪态也必须同步复位：lifecycle 重新到达前
 * 不应再对外报就绪。
 */
class OneBotWsClientForceDisconnectTest extends Specification {

    def dispatcher = Mock(InboundFrameDispatcher)
    def reactionCapability = Mock(ReactionCapability)
    def clock = Mock(Clock)

    def client = new OneBotWsClient(dispatcher, reactionCapability, clock,
            "ws://localhost:1/", "", "", 1000, 2d, 60000, 3, 800, 30, 3)

    private void inject(String fieldName, Object value) {
        Field field = OneBotWsClient.getDeclaredField(fieldName)
        field.setAccessible(true)
        field.set(client, value)
    }

    def "主动断开时复位就绪态abort连接并显式放行重连信号"() {
        given: "一条已就绪的连接与其关闭信号"
        def socket = Mock(WebSocket)
        def closedSignal = new CountDownLatch(1)
        inject("webSocket", socket)
        inject("connectionClosedSignal", closedSignal)
        inject("ready", true)

        when:
        client.forceDisconnect(socket)

        then: "连接被 abort"
        1 * socket.abort()

        and: "就绪态复位（lifecycle 重新到达前不报就绪）"
        !client.isReady()

        and: "重连信号已放行——abort 不保证触发 Listener 回调，这里必须显式倒数"
        closedSignal.count == 0
    }

    def "连接实例为null时仍放行重连信号不抛异常"() {
        given: "连接尚未建立（或已被并发清除）但关闭信号存在"
        def closedSignal = new CountDownLatch(1)
        inject("webSocket", null)
        inject("connectionClosedSignal", closedSignal)

        when:
        client.forceDisconnect(null)

        then: "重连循环照样被放行，不被 null 连接卡住"
        closedSignal.count == 0
    }

    def "关闭信号尚未创建时仅abort与复位就绪态不抛异常"() {
        given: "connectOnce 尚未赋值信号的窗口期"
        def socket = Mock(WebSocket)
        inject("webSocket", socket)
        inject("connectionClosedSignal", null)
        inject("ready", true)

        when:
        client.forceDisconnect(socket)

        then:
        1 * socket.abort()
        !client.isReady()
        noExceptionThrown()
    }
}
