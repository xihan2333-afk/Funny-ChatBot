package cn.edu.scut.chatbot.contract.in;

/**
 * 群消息发送者资料（data-model.md 第 4 节）。OneBot 来源：群消息事件的 sender 对象。
 *
 * <p>匿名消息时 sender 字段仅 best-effort（OneBot 规范原文「尽最大努力提供」），
 * 匿名结构原样进信封 extension（ext.qq.anonymous），标准字段不承载匿名语义。
 *
 * @param userId   发送者 QQ 号字符串
 * @param nickname 昵称
 * @param card     群名片，空串常见
 * @param role     owner / admin / member / unknown
 * @param level    群等级
 * @param title    群头衔
 */
public record SenderProfile(
        String userId,
        String nickname,
        String card,
        String role,
        String level,
        String title
) {
}
