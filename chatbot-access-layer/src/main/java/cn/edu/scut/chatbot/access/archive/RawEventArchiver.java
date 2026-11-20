package cn.edu.scut.chatbot.access.archive;

import cn.edu.scut.chatbot.contract.envelope.RawEventRef;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 原始报文留档服务（data-model.md 第 8 节）：报文原文 JSON 全量落 raw_event_log，
 * 不做任何加工——「信息不丢失」的最终落点。
 *
 * <p>写入时机在 Codec 翻译之前（翻译崩了原文已在）；留档与 XADD 不在一个事务，
 * 顺序是先留档后 XADD，Redis 失败时原文已留档（保真优先于去重，重推允许留档表多行副本）。
 */
@Component
public class RawEventArchiver {

    public static final String DIRECTION_IN = "in";
    public static final String DIRECTION_OUT = "out";

    private final RawEventLogMapper mapper;
    private final Clock clock;
    private final ZoneId zoneId;

    public RawEventArchiver(RawEventLogMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
        this.zoneId = clock.getZone();
    }

    /**
     * 留档一条报文原文并返回留档引用（供信封 rawRef 回指）。
     *
     * @param direction        in 入报文 / out 出报文
     * @param eventType        平台事件类型，解析可得时填（如 message.group），不可得传 null
     * @param dedupKey         平台原生事件 ID / message_id，翻译前留档尚不可得时传 null
     * @param occurredAtMillis 平台侧事件时间（epoch 毫秒），不可得传 null
     */
    public RawEventRef archive(String platform, String botId, String direction, String eventType,
                               String dedupKey, Long occurredAtMillis, String rawPayload) {
        RawEventLogDO row = new RawEventLogDO();
        row.setPlatform(platform);
        row.setBotId(botId);
        row.setDirection(direction);
        row.setEventType(eventType);
        row.setDedupKey(dedupKey);
        row.setRawPayload(rawPayload);
        row.setOccurredAt(toLocalDateTime(occurredAtMillis));
        row.setReceivedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.millis()), zoneId));
        mapper.insertRawEventLog(row);
        return new RawEventRef(platform, String.valueOf(row.getId()), RawEventRef.FORMAT_JSON);
    }

    private LocalDateTime toLocalDateTime(Long epochMillis) {
        return epochMillis == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId);
    }
}
