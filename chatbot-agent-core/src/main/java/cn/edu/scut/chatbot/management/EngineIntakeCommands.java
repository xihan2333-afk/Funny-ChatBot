package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;

/**
 * 入口域命令门面（契约 engine-commands.md）：消费线程调用。
 *
 * <p>命名纪律（请求与事实分离）：祈使式=请求可被拒绝（CommandRejectedException 是合法
 * 返回路径）；过去式=事实只做登记（预期内失败是数据不抛异常）。
 */
public interface EngineIntakeCommands {

    /**
     * 登记触发器（事实，幂等）：uk_dedup 拒插即重复——重复投递只登记一次。
     * 群自动发现（信封带 groupId 而群表无此群 → 同事务 UPSERT）在实现内完成。
     */
    void registerTrigger(EventEnvelope envelope);

    /**
     * 投递回执事实登记（过去式）：按 outboxId 结算 Outbox 行并推进 Turn。
     */
    void settleDelivery(EventEnvelope deliveryReportEnvelope);
}
