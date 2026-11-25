package cn.edu.scut.chatbot.memory.social;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

/**
 * 主动开口策略（contracts/social-and-supply.md §4）：供给 AutonomyScheduler 的判定参数。
 * 内部触发照常过 L0 / L1 闸门（registerInternalTrigger 既有管线，不设旁路）——
 * 本策略只决定「何时产生触发」，不决定「触发是否成行」。
 */
public interface ProactivePolicy {

    /**
     * 生效参数（全部 system_param 热变更 + 群体画像与情绪微调后的最终值）
     */
    ProactiveParams effectiveParams(GroupKey group);

    /**
     * 判定参数（AutonomyScheduler 消费）：
     * silenceBreakMinutes 破冰静默阈值 / minIntervalHours 两次主动最小间隔 /
     * dailyBudget 当日主动预算 / activeHours 活跃时段（安静时段不产生破冰触发）
     */
    record ProactiveParams(int silenceBreakMinutes, int minIntervalHours,
                           int dailyBudget, java.util.Set<Integer> activeHours) {

        /**
         * 无画像数据时的默认活跃时段：8~22 点（保守默认——凌晨不破冰）
         */
        public static java.util.Set<Integer> defaultActiveHours() {
            java.util.Set<Integer> hours = new java.util.TreeSet<>();
            for (int hour = 8; hour <= 22; hour++) {
                hours.add(hour);
            }
            return hours;
        }

        public long silenceBreakMillis() {
            return silenceBreakMinutes * 60_000L;
        }

        public long minIntervalMillis() {
            return minIntervalHours * 3_600_000L;
        }

        /**
         * 当前时刻是否处于活跃时段（判定按业务时钟的小时）
         */
        public boolean isActiveHour(java.time.LocalDateTime now) {
            return activeHours.contains(now.getHour());
        }
    }
}
