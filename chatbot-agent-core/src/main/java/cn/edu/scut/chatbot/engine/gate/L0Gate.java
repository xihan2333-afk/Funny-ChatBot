package cn.edu.scut.chatbot.engine.gate;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * L0 规则闸门（设计方案 2.2.1、spec US2）：纯代码判定，不调模型。
 *
 * <p>规则序（先命中先出）：
 * <ol>
 *   <li>Bot 自身消息一律忽略（防自我激励循环——即使 @ 自己也忽略）</li>
 *   <li>群暂停 → 强制忽略（暂停期间新触发器照常登记但不评估）</li>
 *   <li>黑名单用户（即使 @bot）→ 强制忽略</li>
 *   <li>@提及与斜杠指令 → 强制接受</li>
 *   <li>冷却期内 → 强制忽略</li>
 *   <li>频率超限 → 强制忽略</li>
 *   <li>其余 → 升级 L1 批量评估</li>
 * </ol>
 *
 * <p>守护纪律：先评估后行动，绝大多数事件被安静忽略是正常状态（宪法第三条）。
 */
@Component
public class L0Gate {

    /**
     * L0 判定输入（调用方组装：事件形态 + 群配置 + 群状态）
     */
    public record L0Context(
            String eventType,
            String senderUserId,
            boolean atBot,
            boolean slashCommand,
            boolean botSelfMessage,
            boolean groupPaused,
            List<String> userBlacklist,
            long cooldownRemainingMillis,
            int sendCountInWindow,
            int rateLimitPerMinute
    ) {
    }

    /**
     * L0 判定输出：decision=accept/ignore + reason 留档；escalateToL1=其余情况升级
     */
    public record L0Decision(String decision, String decisionLayer, String reason, boolean escalateToL1) {

        static L0Decision accept(String reason) {
            return new L0Decision("accept", "L0", reason, false);
        }

        static L0Decision ignore(String reason) {
            return new L0Decision("ignore", "L0", reason, false);
        }

        static L0Decision escalate() {
            return new L0Decision("pending", null, "L0 无强制规则命中，升级 L1 注意力评估", true);
        }
    }

    public L0Decision judge(L0Context context) {
        // 1. Bot 自身消息：防自我激励循环（一切形态一律忽略）
        if (context.botSelfMessage()) {
            return L0Decision.ignore("Bot 自身消息回流，一律忽略（防自我激励）");
        }
        // 2. 群暂停：新触发器照常登记但不评估
        if (context.groupPaused()) {
            return L0Decision.ignore("群已暂停，触发器登记但不评估");
        }
        // 3. 黑名单（即使 @bot 也忽略——黑名单优先于强制接受）
        if (context.senderUserId() != null
                && context.userBlacklist() != null
                && context.userBlacklist().contains(context.senderUserId())) {
            return L0Decision.ignore("用户在黑名单（user_blacklist）");
        }
        // 4. @提及与斜杠指令：强制接受
        if (context.atBot()) {
            return L0Decision.accept("被 @提及，强制接受");
        }
        if (context.slashCommand()) {
            return L0Decision.accept("斜杠指令，强制接受");
        }
        // 5. 冷却期：Bot 刚发过言，收敛
        if (context.cooldownRemainingMillis() > 0) {
            return L0Decision.ignore("Bot 发言冷却期内（剩 " + context.cooldownRemainingMillis() + "ms）");
        }
        // 6. 频率上限：窗口内发言条数超限
        if (context.sendCountInWindow() >= context.rateLimitPerMinute()) {
            return L0Decision.ignore("60 秒窗口发言数已达上限 " + context.rateLimitPerMinute());
        }
        // 7. 其余：升级 L1（安静忽略是 L1 的正常结论）
        return L0Decision.escalate();
    }
}
