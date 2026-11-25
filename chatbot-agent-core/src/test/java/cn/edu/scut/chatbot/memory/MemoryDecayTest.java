// 守护纪律：衰减读写共用同一纯函数防口径漂移（contracts/sediment-pipeline.md §5）；
// 固定时钟拨 30 天精确断言双时间轴（occurredAt 越久远衰减越快、lastAccessedAt 越近衰减越慢）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.memory.entry.MemoryDecay;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryDecayTest {

    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");

    private static final MemoryDecay.DecayParams PARAMS = MemoryDecay.DecayParams.defaults();

    private static final long DAY = 24L * 3600 * 1000;

    @Test
    void 双时间轴条目强度精确低于单时间轴条目_发生与闲置都久忘得更快() {
        // 双时间轴：30 天前发生且 30 天前最后被访问（衰减时钟跑了 30 天）
        double dualAxis = MemoryDecay.effectiveStrength(50,
                NOW.minusMillis(30 * DAY), NOW.minusMillis(30 * DAY), NOW, PARAMS);
        // 单时间轴：30 天前发生、但一直被检索到最近（衰减时钟刚清零）
        double singleAxis = MemoryDecay.effectiveStrength(50,
                NOW.minusMillis(30 * DAY), NOW, NOW, PARAMS);

        assertThat(dualAxis).isLessThan(singleAxis);
        // 精确值：30 天前发生 → 速率 1.0 × 2^1 = 2.0；跑了 30 天 → 50 − 60 → 钳 0
        assertThat(dualAxis).isZero();
        assertThat(singleAxis).isEqualTo(50.0);
    }

    @Test
    void 常被检索条目强度显著更高_检索热度抵消衰减() {
        // 同为 30 天前发生：闲置 10 天 vs 闲置 2 天
        double idle10 = MemoryDecay.effectiveStrength(80,
                NOW.minusMillis(30 * DAY), NOW.minusMillis(10 * DAY), NOW, PARAMS);
        double idle2 = MemoryDecay.effectiveStrength(80,
                NOW.minusMillis(30 * DAY), NOW.minusMillis(2 * DAY), NOW, PARAMS);

        assertThat(idle2).isGreaterThan(idle10);
        // 精确值：速率 2.0；80 − 2×10 = 60；80 − 2×2 = 76
        assertThat(idle10).isEqualTo(60.0);
        assertThat(idle2).isEqualTo(76.0);
    }

    @Test
    void 发生越久远衰减速率越快_每满30天翻倍() {
        // 昨天发生（速率 1.0）、30 天前发生（速率 2.0）、60 天前发生（速率 4.0），
        // 都闲置 5 天：衰减量 5 / 10 / 20
        assertThat(MemoryDecay.effectiveStrength(50, NOW.minusMillis(DAY),
                NOW.minusMillis(5 * DAY), NOW, PARAMS)).isEqualTo(45.0);
        assertThat(MemoryDecay.effectiveStrength(50, NOW.minusMillis(30 * DAY),
                NOW.minusMillis(5 * DAY), NOW, PARAMS)).isEqualTo(40.0);
        assertThat(MemoryDecay.effectiveStrength(50, NOW.minusMillis(60 * DAY),
                NOW.minusMillis(5 * DAY), NOW, PARAMS)).isEqualTo(30.0);
    }

    @Test
    void 从未被检索的条目_衰减自发生时刻起算() {
        // lastAccessedAt 为 null：staleDays = ageDays = 10；昨天发生（速率 1.0）→ 50 − 10 = 40
        assertThat(MemoryDecay.effectiveStrength(50,
                NOW.minusMillis(10 * DAY), null, NOW, PARAMS)).isEqualTo(40.0);
    }

    @Test
    void 边界钳制_下限0上限100_未来时间不倒涨() {
        assertThat(MemoryDecay.effectiveStrength(5,
                NOW, NOW, NOW, PARAMS)).isEqualTo(5.0);
        assertThat(MemoryDecay.effectiveStrength(5,
                NOW.minusMillis(365 * DAY), NOW.minusMillis(365 * DAY), NOW, PARAMS)).isZero();
        // 发生在未来（脏数据）不产生负衰减
        assertThat(MemoryDecay.effectiveStrength(50,
                NOW.plusMillis(DAY), NOW, NOW, PARAMS)).isEqualTo(50.0);
    }

    @Test
    void 衰减因子形态_强度归一_零强度条目因子为零() {
        assertThat(MemoryDecay.decayFactor(50, NOW.minusMillis(DAY),
                NOW.minusMillis(2 * DAY), NOW, PARAMS)).isEqualTo(0.96);
        assertThat(MemoryDecay.decayFactor(0, NOW, NOW, NOW, PARAMS)).isZero();
    }

    @Test
    void dailyDecayRate可调_遗忘节奏热变更() {
        MemoryDecay.DecayParams slow = new MemoryDecay.DecayParams(15, 180, 5000, 0.5);
        // 昨天发生（速率 0.5）、闲置 4 天：50 − 0.5×4 = 48
        assertThat(MemoryDecay.effectiveStrength(50, NOW.minusMillis(DAY),
                NOW.minusMillis(4 * DAY), NOW, slow)).isEqualTo(48.0);
    }
}
