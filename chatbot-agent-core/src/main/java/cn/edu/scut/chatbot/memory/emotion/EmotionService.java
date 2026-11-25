package cn.edu.scut.chatbot.memory.emotion;

import cn.edu.scut.chatbot.bootstrap.GroupKey;

/**
 * 运行时情绪服务（contracts/persona-and-emotion.md §5）：可推导暂态——
 * 读时纯函数懒衰减（半衰期 2h 向人格基线回归、低于阈值视为基线）、
 * 群暂停计时顺延、无任何定时器（负向断言口径）。
 *
 * <p>写双通道（规则保即时、自报保语义）共用钳制：单次 |delta| ≤ 30、
 * 当日 |delta| 累计封顶——群友轮番刷情绪也只能推动有限幅度。
 * 规则通道一次事件双落点：情绪 delta 落 Agent 整体 + 主体好感 delta
 * 顺带落 user_profile。情绪改变「怎么说」永不改变「该不该说」（L0 不受影响）、
 * 不解锁任何权限（数值永不进权限判定）。
 */
public interface EmotionService {

    /**
     * 读：此刻应有的心情（懒衰减纯函数、暂停顺延——不写库）
     */
    EmotionState read(GroupKey group);

    /**
     * 规则通道：互动事件即时微调（delta = 规则表 × 人格敏感度，人格覆盖反转方向；
     * label 不由规则通道改——语义事件只有模型读得懂，走自报通道）。
     * 双落点：主体好感 delta 顺带落画像。返回 false = 日累计封顶被拒。
     */
    boolean applyRuleDelta(GroupKey group, String eventKind, String subjectUserId);

    /**
     * 自报通道（updateEmotion 工具内核）：词汇集校验 + label 显式变化。
     *
     * @throws IllegalArgumentException 词汇集外标签
     */
    boolean applySelfReport(GroupKey group, String moodLabel, int intensityDelta, String cause);

    /**
     * 管理门面：查看当前情绪与最近起因（有效值口径同 read）
     */
    EmotionState view(GroupKey group);

    /**
     * 管理门面：一键重置回人格基线
     */
    void resetToBaseline(GroupKey group);
}
