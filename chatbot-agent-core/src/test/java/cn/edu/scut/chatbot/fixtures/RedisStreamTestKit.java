package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Redis 流测试工具（research R9：agent-core 端到端测试直接在流上操作——XADD 注入入流、
 * XREAD 断言出流，不用假 NapCat）。端到端剧本的操作面。
 */
public final class RedisStreamTestKit {

    private static final byte[] ENVELOPE_FIELD = "envelope".getBytes(StandardCharsets.UTF_8);

    private final StringRedisTemplate redisTemplate;

    public RedisStreamTestKit(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * XADD 一条信封到入流（模拟接入层产出的事件）
     */
    public String addToInbound(String inStreamKey, EventEnvelope envelope) {
        return addEnvelope(inStreamKey, EnvelopeFactory.toJson(envelope));
    }

    /**
     * XADD 一条原始 JSON 信封（手工构造的坏消息等负向用例）
     */
    public String addEnvelope(String streamKey, String envelopeJson) {
        MapRecord<byte[], byte[], byte[]> record = StreamRecords.newRecord()
                .in(streamKey.getBytes(StandardCharsets.UTF_8))
                .ofMap(Map.of(ENVELOPE_FIELD, envelopeJson.getBytes(StandardCharsets.UTF_8)));
        return redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xAdd(record, RedisStreamCommands.XAddOptions.none())).getValue();
    }

    /**
     * XRANGE 读出流全部现存消息（从 0 起；返回信封 JSON 列表）
     */
    public List<String> readOutboundAll(String outStreamKey) {
        return toEnvelopeJson(redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xRange(outStreamKey.getBytes(StandardCharsets.UTF_8),
                        Range.unbounded())));
    }

    /**
     * XRANGE 按范围读（开区间从 fromId 之后；轮询新消息断言用）
     */
    public List<String> readOutboundSince(String outStreamKey, String fromId) {
        return toEnvelopeJson(redisTemplate.execute((RedisConnection conn) ->
                conn.streamCommands().xRange(outStreamKey.getBytes(StandardCharsets.UTF_8),
                        Range.open(fromId, "+"))));
    }

    private List<String> toEnvelopeJson(List<? extends MapRecord<byte[], byte[], byte[]>> records) {
        if (records == null) {
            return List.of();
        }
        return records.stream().map(record -> {
            // byte[] 作 Map key 是引用比较，不能直接 get(ENVELOPE_FIELD)——按内容逐项比对
            // （与 RedisStreamGateway 读流同款读法）
            for (Map.Entry<byte[], byte[]> entry : record.getValue().entrySet()) {
                if (java.util.Arrays.equals(entry.getKey(), ENVELOPE_FIELD) && entry.getValue() != null) {
                    return new String(entry.getValue(), StandardCharsets.UTF_8);
                }
            }
            return "";
        }).toList();
    }

    /**
     * 清空流内全部消息但保留消费组（测试隔离）。不能 DEL：DEL 会连消费组一起删，
     * 而组只在消费端启动时建一次，删了就永远 NOGROUP——XTRIM 0 只清消息不动组
     */
    public void clearStream(String streamKey) {
        redisTemplate.opsForStream().trim(streamKey, 0L);
    }
}
