package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.bootstrap.IdGenerator;

/**
 * 顺序可控发号假件（宪法测试纪律「假发号器列入测试基建」）：固定时钟静止 + 真发号器，
 * 同毫秒内序列 0,1,2… 递增——ID 值可预测（时间戳分量恒定、机器号 1），测试可精确断言
 * 落库行 ID 与引用关系。与 001 接入层测试基建同款思路（固定时钟下的真发号器即假发号器）。
 */
public class SeededIdGenerator extends IdGenerator {

    public SeededIdGenerator() {
        // 机器号 1（agent-core 默认），时钟静止在 2026-05-01T08:00:00Z
        super(1, new FixedClock(1775001600000L));
    }
}
