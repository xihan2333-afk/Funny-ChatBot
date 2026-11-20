package cn.edu.scut.chatbot.access.infrastructure

import cn.edu.scut.chatbot.access.fixtures.RedisTemplateBridges
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisStreamCommands
import org.springframework.data.redis.connection.stream.ByteRecord
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.PendingMessage
import org.springframework.data.redis.connection.stream.PendingMessages
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import spock.lang.Specification

import java.time.Duration

/**
 * 守护纪律：Redis Stream 传输契约（contracts/redis-streams.md）。
 * XADD 必须带单字段 envelope 与 MAXLEN ~ 100000 近似裁剪（Stream 是传输带不是账本）；
 * 单元测试 Mock 一切协作者（宪法第七条），逐条命令断言参数形态。
 */
class StreamGatewayTest extends Specification {

    def redisTemplate = Mock(StringRedisTemplate)
    def connection = Mock(RedisConnection)
    def streamCommands = Mock(RedisStreamCommands)
    def gateway = new StreamGateway(redisTemplate)

    /** 把 template.execute(callback) 桥接到 Mock 连接上（Java 侧调用，绕开 Groovy 对 lambda 隐藏类的分派缺陷） */
    private void bridgeExecute() {
        connection.streamCommands() >> streamCommands
        redisTemplate.execute(_ as RedisCallback) >> { RedisCallback callback ->
            RedisTemplateBridges.executeOn(callback, connection)
        }
    }

    def "XADD携带单字段envelope与近似裁剪MAXLEN十万"() {
        given:
        bridgeExecute()

        when:
        def recordId = gateway.appendEnvelope("chatbot:in:onebot-v11", '{"type":"raw_event"}')

        then: "命令参数断言：流键、单字段 envelope、近似裁剪 MAXLEN 100000"
        1 * streamCommands.xAdd({ MapRecord<byte[], byte[], byte[]> record ->
            def entry = record.getValue().entrySet().iterator().next()
            new String(entry.key as byte[]) == "envelope" &&
                    new String(entry.value as byte[]) == '{"type":"raw_event"}' &&
                    new String(record.stream as byte[]) == "chatbot:in:onebot-v11"
        }, { options ->
            options.maxlen == StreamGateway.MAXLEN_APPROX && options.approximateTrimming
        }) >> RecordId.of("1-1")

        and:
        recordId.value == "1-1"
    }

    def "建消费组不存在时以MKSTREAM从头offset创建"() {
        given:
        bridgeExecute()

        when:
        gateway.ensureConsumerGroup("chatbot:out:onebot-v11", "access-layer")

        then:
        1 * streamCommands.xGroupCreate({ new String(it as byte[]) == "chatbot:out:onebot-v11" },
                "access-layer", _, true) >> "OK"
    }

    def "消费组已存在BUSYGROUP时静默忽略不抛异常"() {
        given:
        bridgeExecute()
        streamCommands.xGroupCreate(_, _, _, _) >> {
            throw new IllegalStateException("BUSYGROUP Consumer Group name already exists")
        }

        when:
        gateway.ensureConsumerGroup("chatbot:out:onebot-v11", "access-layer")

        then:
        noExceptionThrown()
    }

    def "读组消息还原recordId与envelope字段值"() {
        given:
        bridgeExecute()
        def byteRecord = StreamRecords.newRecord()
                .in("chatbot:out:onebot-v11".bytes)
                .ofMap(Map.of("envelope".bytes, '{"type":"send_group_message"}'.bytes))
                .withId(RecordId.of("42-0")) as ByteRecord
        streamCommands.xReadGroup(_, _, _) >> List.of(byteRecord)

        when:
        def messages = gateway.readNewMessages("chatbot:out:onebot-v11", "access-layer", "c1", 16, 60000)

        then:
        messages.size() == 1
        messages[0].recordId() == "42-0"
        messages[0].envelope() == '{"type":"send_group_message"}'
    }

    def "pending扫描按空闲阈值过滤并带回投递次数"() {
        given:
        bridgeExecute()
        def idleMessage = new PendingMessage(
                RecordId.of("10-0"),
                Consumer.from("access-layer", "access-layer-1"),
                Duration.ofMillis(90_000), 3)
        def freshMessage = new PendingMessage(
                RecordId.of("11-0"),
                Consumer.from("access-layer", "access-layer-1"),
                Duration.ofMillis(5_000), 1)
        streamCommands.xPending(_ as byte[], "access-layer", _) >> new PendingMessages("access-layer",
                List.of(idleMessage, freshMessage))

        when:
        def beyond = gateway.pendingIdleBeyond("chatbot:out:onebot-v11", "access-layer", 60_000, 64)

        then: "只有空闲超过 60 秒的消息入选，投递次数带回供毒消息告警判定"
        beyond.size() == 1
        beyond[0].recordId() == "10-0"
        beyond[0].idleMillis() == 90_000
        beyond[0].deliveryCount() == 3
    }

    def "XCLAIM把超时空闲消息认领到本消费者名下"() {
        given:
        bridgeExecute()
        def claimed = StreamRecords.newRecord()
                .in("chatbot:out:onebot-v11".bytes)
                .ofMap(Map.of("envelope".bytes, '{"type":"recall_message"}'.bytes))
                .withId(RecordId.of("10-0")) as ByteRecord
        streamCommands.xClaim(_, _, _, _) >> List.of(claimed)

        when:
        def messages = gateway.claimIdle("chatbot:out:onebot-v11", "access-layer", "access-layer-1",
                60_000, ["10-0"])

        then:
        messages.size() == 1
        messages[0].recordId() == "10-0"
        messages[0].envelope() == '{"type":"recall_message"}'
    }

    def "XACK携带流键消费组与消息ID列表且返回确认数"() {
        given:
        bridgeExecute()

        when:
        def acknowledged = gateway.acknowledge("chatbot:out:onebot-v11", "access-layer", ["1-1", "2-0"])

        then: "命令参数断言：流键、组名、逐条 ID"
        1 * streamCommands.xAck({ new String(it as byte[]) == "chatbot:out:onebot-v11" },
                "access-layer", RecordId.of("1-1"), RecordId.of("2-0")) >> 2L

        and:
        acknowledged == 2L
    }

    def "XACK对空列表与null列表不发命令直接返回零"() {
        when:
        def emptyResult = gateway.acknowledge("chatbot:out:onebot-v11", "access-layer", [])

        then:
        emptyResult == 0
        0 * streamCommands.xAck(_, _, _)

        when:
        def nullResult = gateway.acknowledge("chatbot:out:onebot-v11", "access-layer", null)

        then:
        nullResult == 0
        0 * streamCommands.xAck(_, _, _)
    }

    def "XCLAIM对空ID列表不发命令返回空列表"() {
        when:
        def messages = gateway.claimIdle("chatbot:out:onebot-v11", "access-layer", "access-layer-1", 60_000, [])

        then:
        messages.isEmpty()
        0 * streamCommands.xClaim(_, _, _, _)
    }

    def "读组消息无消息返回空列表而非null"() {
        given: "BLOCK 超时或组内无新消息时命令返回 null"
        bridgeExecute()
        streamCommands.xReadGroup(_, _, _) >> null

        when:
        def messages = gateway.readNewMessages("chatbot:out:onebot-v11", "access-layer", "c1", 16, 60000)

        then:
        messages != null
        messages.isEmpty()
    }

    def "pending无积压返回空列表而非null"() {
        given:
        bridgeExecute()
        streamCommands.xPending(_ as byte[], _, _) >> null

        when:
        def beyond = gateway.pendingIdleBeyond("chatbot:out:onebot-v11", "access-layer", 60_000, 64)

        then:
        beyond != null
        beyond.isEmpty()
    }
}
