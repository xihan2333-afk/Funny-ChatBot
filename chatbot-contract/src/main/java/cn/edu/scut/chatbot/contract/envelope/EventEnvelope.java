package cn.edu.scut.chatbot.contract.envelope;

import java.util.Map;

/**
 * 事件信封：Redis Stream 双流上唯一传输单元（data-model.md 第 2 节）。
 *
 * <p>入方向（接入层 → 主服务）：eventId 有值、outboxId 为 null；
 * 出方向（主服务 → 接入层）：outboxId 有值、eventId 为 null。DeliveryReport 虽走入流，
 * 但由接入层自产，outboxId 关联的是它回执的那条出事件。
 *
 * <p>线格式纪律（contracts/event-envelope.md）：可空字段序列化时显式输出 null 不省略键，
 * 消费端判空逻辑统一；枚举值一律字符串短码；载荷不出现 Java 类名与 @class 类型信息，
 * 多态由信封顶层 {@code type} 字段查映射表解释——两端类名解耦。
 *
 * <p>非空清单校验（入 / 出两套，data-model 第 2 节「验证规则」）是接入层的结构断言，
 * 归属接入层 EnvelopeValidator 执行——本 record 只承载数据，契约模块零行为逻辑。
 *
 * @param eventId      雪花十进制串，接入层发放，仅链路追踪不承担幂等；出事件为 null
 * @param outboxId     雪花十进制串，Agent 侧先落库发放，回执以此关联；入事件为 null
 * @param dedupKey     Agent 消费端幂等键：消息类取平台 message_id，notice/request 类退化为报文内容哈希；出事件为 null
 * @param platform     平台短码，取值见 {@link PlatformCodes}
 * @param botId        机器人账号（OneBot 事件 self_id 的字符串化），非空
 * @param groupId      群号；群维度事件必填，私聊与 Bot 全局事件为 null
 * @param userId       发起者或目标用户；群体级事件为 null
 * @param type         原语类型短码，入出共用一个字符串空间，取值见 {@link PrimitiveTypeCodes}
 * @param payload      十三种原语 record 之一，按 type 多态
 * @param occurredAt   平台侧事件时间（epoch 毫秒）。注意：OneBot time 字段是秒级，Codec 已换算为毫秒；出事件为 null
 * @param receivedAt   接入层接收时刻（epoch 毫秒），由注入的 Clock 打点；出事件为 null
 * @param extension    平台特有命名空间，键形如 ext.qq.*；Agent 核心默认不读
 * @param rawRef       平台原始报文在 raw_event_log 留档表中的引用；DeliveryReport 自产时为 null
 * @param traceContext W3C traceparent 字符串，贯穿入出与回执全程
 */
public record EventEnvelope(
        String eventId,
        String outboxId,
        String dedupKey,
        String platform,
        String botId,
        String groupId,
        String userId,
        String type,
        EnvelopePayload payload,
        Long occurredAt,
        Long receivedAt,
        Map<String, String> extension,
        RawEventRef rawRef,
        String traceContext
) {
}
