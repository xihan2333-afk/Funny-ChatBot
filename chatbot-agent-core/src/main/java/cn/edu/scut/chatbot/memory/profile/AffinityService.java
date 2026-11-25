package cn.edu.scut.chatbot.memory.profile;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

/**
 * 双轴态度服务（contracts/social-and-supply.md §5）：数值层管方向与强度
 * （好感 -100~+100 存原始值读时懒衰减；熟悉度 0~100 只增不衰减），档位是数值到
 * 语义的映射（L1 调制与态度行的措辞依据）。
 *
 * <p>防操纵四条的执行点：只认统计信号不认指令（统计通道零文本语义解析——
 * 只数结构化互动段）；单日幅度钳制（统计好感日上限）；转述打折（语义层归沉淀
 * 抽取管线的「转述的他人言行不进画像信号」提示词纪律，本通道结构性免疫）；
 * 永不越权（本服务只产数值与措辞，不解锁任何行为）。
 */
public interface AffinityService {

    /**
     * 读：好感原始值 + 懒衰减（半衰期 7 天向 0 回归）→ 有效值与档位；无画像行回默认（0/无感）
     */
    AttitudeView view(GroupKey group, String userId);

    /**
     * 互动事件即时微调（增长渐进 |delta| 限 1~3；负好感按下降系数 1.5 加速——记仇也是趣味性；
     * 熟悉度只增：负的 familiarityDelta 直接归零）。同一次事件处理与情绪 delta 一起落（T043 双落点）
     */
    void applyEventDelta(GroupKey group, String userId,
                         int familiarityDelta, int affinityDelta);

    /**
     * 每日沉淀统计主通道（全群遍历）：近一天结构化互动信号汇总——@ bot 的陪伴计数
     * 推好感（日上限钳制）、发言活跃推熟悉度（只增）；interest_tags 从偏好类记忆重算刷新。
     * 好感越界钳 ±100（SQL 层）。幂等可重入。
     */
    void runDailySedimentStatistics();

    /**
     * 单群形态（IT 与编排任务的可直驱入口）
     */
    void runDailySedimentStatisticsForGroup(GroupKey group);

    /**
     * 态度读视图：有效好感（懒衰减后）、档位、熟悉度、称呼回退链数据、互动摘要（基调短语）
     */
    record AttitudeView(int effectiveAffinity, Tier tier, int familiarity,
                        String callName, String platformName, String interactDigest) {

        public static AttitudeView empty() {
            return new AttitudeView(0, Tier.NEUTRAL, 0, null, null, null);
        }
    }

    /**
     * 档位（数值到语义的映射；L1 调制与态度行措辞依据；无感不标注不注入）
     */
    enum Tier {
        BEST_FRIEND("挚友"),
        FRIEND("朋友"),
        NEUTRAL("无感"),
        DISTANT("疏远"),
        HOSTILE("厌恶");

        private final String label;

        Tier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * 无感档不参与 L1 标注与态度行注入
         */
        public boolean significant() {
            return this != NEUTRAL;
        }
    }

    /**
     * 档位映射纯函数（边界归属精确：≥+70 挚友 / ≥+30 朋友 / >−30 无感 / >−70 疏远 / ≤−70 厌恶
     * ——与 +70 属挚友对称，−70 属厌恶）
     */
    static Tier tierOf(int effectiveAffinity) {
        if (effectiveAffinity >= 70) {
            return Tier.BEST_FRIEND;
        }
        if (effectiveAffinity >= 30) {
            return Tier.FRIEND;
        }
        if (effectiveAffinity > -30) {
            return Tier.NEUTRAL;
        }
        if (effectiveAffinity > -70) {
            return Tier.DISTANT;
        }
        return Tier.HOSTILE;
    }
}
