package cn.edu.scut.chatbot.infrastructure.redis;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XPendingOptions;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 读写薄封装（契约 redis-streams-agent-core.md）：与接入层 StreamGateway 同构
 * （两端同一套命令模式是 3.2.2 明文，各自实现不共享类）。入流消费（XREADGROUP/XACK/XCLAIM）
 * 与出流投递（XADD MAXLEN）的全部命令操作集中于此。
 *
 * <p>消息体形态恒定：单字段 {@code envelope}，值为信封 JSON 字符串（不依赖 RedisTemplate
 * 序列化器、不携带 Java 类型头——跨进程类名解耦）。全部走低级 RedisConnection 命令。
 */
@Component
public class RedisStreamGateway {

    /**
     * Stream 近似裁剪上限：传输带不是账本（契约第 2 节）
     */
    public static final long MAXLEN_APPROX = 100000;

    private static final byte[] ENVELOPE_FIELD = "envelope".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redisTemplate;

    public RedisStreamGateway(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 流上一条消息（recordId + envelope 字段的信封 JSON）
     */
    public record StreamMessage(String recordId, String envelope) {
    }

    /**
     * pending 消息摘要：ID + 空闲时长 + 已投递次数
     */
    public record PendingMessageView(String recordId, long idleMillis, long deliveryCount) {
    }

    /**
     * XADD：追加一条信封消息到出流，MAXLEN ~ 100000 近似裁剪
     */
    public RecordId appendEnvelope(String streamKey, String envelopeJson) {
        MapRecord<byte[], byte[], byte[]> record = StreamRecords.newRecord()
                .in(streamKey.getBytes(StandardCharsets.UTF_8))
                .ofMap(Map.of(ENVELOPE_FIELD, envelopeJson.getBytes(StandardCharsets.UTF_8)));
        return redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xAdd(record, XAddOptions.maxlen(MAXLEN_APPROX).approximateTrimming(true)));
    }

    /**
     * XGROUP CREATE：建入流消费组（MKSTREAM；BUSYGROUP 已存在时静默忽略——启动幂等）
     */
    public void ensureConsumerGroup(String streamKey, String groupName) {
        try {
            redisTemplate.execute((RedisConnection conn) -> conn.streamCommands()
                    .xGroupCreate(streamKey.getBytes(StandardCharsets.UTF_8),
                            groupName, ReadOffset.from("0"), true));
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                return;
            }
            throw e;
        }
    }

    /**
     * XREADGROUP（读新消息 ">"）：阻塞批量拉取本组未投递给本消费者的消息
     */
    public List<StreamMessage> readNewMessages(String streamKey, String groupName, String consumerName,
                                               long count, long blockMillis) {
        List<ByteRecord> records = redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xReadGroup(
                        Consumer.from(groupName, consumerName),
                        StreamReadOptions.empty().count(count).block(Duration.ofMillis(blockMillis)),
                        StreamOffset.create(streamKey.getBytes(StandardCharsets.UTF_8), ReadOffset.lastConsumed())));
        return toStreamMessages(records);
    }

    /**
     * XACK：确认消息已处理完成（业务完成才调用——至少一次语义的确认边界）
     */
    public long acknowledge(String streamKey, String groupName, List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return 0;
        }
        RecordId[] ids = recordIds.stream().map(RecordId::of).toArray(RecordId[]::new);
        Long acknowledged = redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xAck(streamKey.getBytes(StandardCharsets.UTF_8), groupName, ids));
        return acknowledged == null ? 0 : acknowledged;
    }

    /**
     * XPENDING：取本组空闲超过 idleMillis 的 pending 消息摘要（Spring Data Redis 2.7 未暴露
     * IDLE 过滤参数，拉取后在 Java 侧筛选——与接入层同款取舍）
     */
    public List<PendingMessageView> pendingIdleBeyond(String streamKey, String groupName,
                                                      long idleMillis, long count) {
        PendingMessages pending = redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xPending(
                        streamKey.getBytes(StandardCharsets.UTF_8),
                        groupName,
                        XPendingOptions.unbounded(count)));
        List<PendingMessageView> views = new ArrayList<>();
        if (pending != null) {
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().toMillis() >= idleMillis) {
                    views.add(new PendingMessageView(
                            message.getId().getValue(),
                            message.getElapsedTimeSinceLastDelivery().toMillis(),
                            message.getTotalDeliveryCount()));
                }
            }
        }
        return views;
    }

    /**
     * XCLAIM：把空闲超过 minIdleMillis 的 pending 消息认领到本消费者名下并取回消息体
     * （重投后走与正常消费同一条分发路径）
     */
    public List<StreamMessage> claimIdle(String streamKey, String groupName, String consumerName,
                                         long minIdleMillis, List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return List.of();
        }
        List<ByteRecord> records = redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xClaim(
                        streamKey.getBytes(StandardCharsets.UTF_8),
                        groupName,
                        consumerName,
                        XClaimOptions.minIdleMs(minIdleMillis)
                                .ids(recordIds.toArray(String[]::new))));
        return toStreamMessages(records);
    }

    private List<StreamMessage> toStreamMessages(List<? extends MapRecord<byte[], byte[], byte[]>> records) {
        if (records == null) {
            return List.of();
        }
        List<StreamMessage> messages = new ArrayList<>(records.size());
        for (MapRecord<byte[], byte[], byte[]> record : records) {
            // byte[] 作 Map key 是引用比较，不能直接 get(ENVELOPE_FIELD)——按内容逐项比对
            String envelope = null;
            for (Map.Entry<byte[], byte[]> entry : record.getValue().entrySet()) {
                if (Arrays.equals(entry.getKey(), ENVELOPE_FIELD) && entry.getValue() != null) {
                    envelope = new String(entry.getValue(), StandardCharsets.UTF_8);
                    break;
                }
            }
            messages.add(new StreamMessage(record.getId().getValue(), envelope));
        }
        return messages;
    }
}
