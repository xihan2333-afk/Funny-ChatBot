package cn.edu.scut.chatbot.infrastructure.es;

/**
 * 记忆与社交报表：沉淀量（memory_entry 窗口计数）、主动开口成功率
 * （trigger_event 的 internal/silenceBreak 决策 accept 占比）。
 *
 * <p>检索命中率 / 遗忘量 / 话题再引入接受率当前无落表事实源（检索与遗忘动作不留痕、
 * 话题再引入无专门事件类型）——列演进项，有落点后补字段，不返回编造值。
 */
public record SocialReport(long sedimentCount,
                           long initiativeTotal,
                           Double initiativeSuccessRate) {
}
