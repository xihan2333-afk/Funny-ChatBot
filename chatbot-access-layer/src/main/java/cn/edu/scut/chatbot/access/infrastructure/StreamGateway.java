package cn.edu.scut.chatbot.access.infrastructure;

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
 * Redis Stream 读写薄封装（契约 contracts/redis-streams.md）：接入层对双流的全部命令操作
 * 集中于此——XADD（MAXLEN 近似裁剪）、建消费组、XREADGROUP、XACK、XPENDING 扫描、XCLAIM 认领。
 *
 * <p>消息体形态恒定：单字段 {@code envelope}，值为信封 JSON 字符串（不用 RedisTemplate 序列化器，
 * 不携带 Java 类型头——跨进程类名解耦）。全部走低级 RedisConnection 命令，参数语义显式。
 */
@Component
public class StreamGateway {

    /**
     * Stream 近似裁剪上限：Stream 是传输带不是账本，历史在留档表与 Agent 转录（契约第 1 节）
     */
    public static final long MAXLEN_APPROX = 100000;

    /**
     * 消息唯一字段名：XADD 的单字段键
     */
    private static final byte[] ENVELOPE_FIELD = "envelope".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redisTemplate;

    public StreamGateway(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 流上一条消息（recordId + envelope 字段的信封 JSON）
     */
    public record StreamMessage(String recordId, String envelope) {
    }

    /**
     * pending 消息摘要：ID + 空闲时长 + 已投递次数（XPENDING 扩展形态的返回）
     */
    public record PendingMessageView(String recordId, long idleMillis, long deliveryCount) {
    }

    /**
     * XADD：追加一条信封消息到流，MAXLEN ~ 100000 近似裁剪。
     *
     * @return Redis 生成的消息 ID
     */
    public RecordId appendEnvelope(String streamKey, String envelopeJson) {
        MapRecord<byte[], byte[], byte[]> record = StreamRecords.newRecord()
                .in(streamKey.getBytes(StandardCharsets.UTF_8))
                .ofMap(Map.of(ENVELOPE_FIELD, envelopeJson.getBytes(StandardCharsets.UTF_8)));
        return redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xAdd(record, XAddOptions.maxlen(MAXLEN_APPROX).approximateTrimming(true)));
    }

    /**
     * XGROUP CREATE：建出流消费组（MKSTREAM——流不存在则同时创建）。组已存在（BUSYGROUP）时静默忽略，
     * 这是启动幂等的既定行为。offset 取 "0"：从头消费，不跳过建组前积压的出事件（至少一次语义）。
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
     * XREADGROUP（读新消息 "："lastConsumed）：阻塞读批量拉取本组未投递给本消费者的消息。
     *
     * @return 空列表表示本轮无消息（BLOCK 超时或组内无新消息）
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
     * XPENDING 扩展形态：取本组空闲超过 idleMillis 的 pending 消息摘要，
     * 供 XCLAIM 重投循环筛选（投递次数同时带回，超限转 adapter.stream.poison 告警）。
     *
     * <p>Spring Data Redis 2.7 的 XPENDING 抽象没有暴露 Redis 原生的 IDLE 过滤参数，
     * 此处拉取全量 pending（count 上限）后在 Java 侧按空闲时长筛选——行为与
     * {@code XPENDING key group IDLE ms - + count} 等价，pending 规模在单实例消费下远小于 count 上限。
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
     * （重投后走与正常消费同一条分发路径——R5 决策）。
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
