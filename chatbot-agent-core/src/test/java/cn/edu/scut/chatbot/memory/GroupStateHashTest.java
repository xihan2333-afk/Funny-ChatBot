// 守护纪律：群状态 Hash 扩容不新增键、既有四字段语义不变、窗口读取时衰减（T049）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T049：GroupStateHash 六状态项扩容。Hash 语义用内存 Map 支撑（单测纪律——Mock 一切协作者，
 * 不起真 Redis；真容器形态归 GroupStateAndProactiveIT）。
 */
class GroupStateHashTest {

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private MutableClock clock;
    private GroupStateHash groupStateHash;

    /**
     * 可拨时钟（窗口衰减断言的基准）
     */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void forward(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        // 内存 Map 支撑 Hash 读写（键固定单群——本测试只关心字段语义不关心键空间）
        Map<Object, Object> store = new java.util.HashMap<>();
        when(hashOperations.get(anyString(), any())).thenAnswer(inv -> store.get(inv.getArgument(1)));
        Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(hashOperations).put(anyString(), any(), any());
        when(hashOperations.increment(anyString(), any(), anyLong())).thenAnswer(inv -> {
            Object current = store.get(inv.getArgument(1));
            long delta = inv.getArgument(2, Long.class);
            long next = (current == null ? 0 : Long.parseLong(String.valueOf(current))) + delta;
            store.put(inv.getArgument(1), String.valueOf(next));
            return next;
        });
        clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
        groupStateHash = new GroupStateHash(redisTemplate, clock);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @Test
    void 群消息记账_刷新lastMsgAt并累加速率窗口与活跃成员() {
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "userA", clock.millis());
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "userA", clock.millis());
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "userB", clock.millis());

        assertEquals(3, groupStateHash.messageRateCount("qq", "bot1", "g1"));
        assertEquals(clock.millis(), groupStateHash.lastMsgAt("qq", "bot1", "g1"));
        Map<String, Long> members = groupStateHash.activeMembersRecent("qq", "bot1", "g1");
        assertEquals(2, members.size());
        assertEquals(clock.millis(), members.get("userA"));
    }

    @Test
    void 速率窗口推进一小时_旧消息计数读取时衰减归零() {
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "userA", clock.millis());
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "userB", clock.millis());
        assertEquals(2, groupStateHash.messageRateCount("qq", "bot1", "g1"));

        clock.forward(Duration.ofHours(1));
        assertEquals(0, groupStateHash.messageRateCount("qq", "bot1", "g1"),
                "窗口已过——读取时归零（事件累加 + 读时衰减语义）");

        // 过窗后新消息重开窗口
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "userA", clock.millis());
        assertEquals(1, groupStateHash.messageRateCount("qq", "bot1", "g1"));
    }

    @Test
    void 活跃成员超出24小时窗口_读取时被裁剪() {
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "staleUser", clock.millis());
        clock.forward(Duration.ofHours(25));
        groupStateHash.recordGroupMessage("qq", "bot1", "g1", "freshUser", clock.millis());

        Map<String, Long> members = groupStateHash.activeMembersRecent("qq", "bot1", "g1");
        assertEquals(Map.of("freshUser", clock.millis()), members,
                "25 小时前的成员已出窗口被裁剪、刚活跃的保留");
    }

    @Test
    void Agent发言24h统计_窗口内累加过窗重置() {
        groupStateHash.recordAgentSend24h("qq", "bot1", "g1", clock.millis());
        groupStateHash.recordAgentSend24h("qq", "bot1", "g1", clock.millis());
        assertEquals(2, groupStateHash.agentSend24hCount("qq", "bot1", "g1"));

        clock.forward(Duration.ofHours(25));
        assertEquals(0, groupStateHash.agentSend24hCount("qq", "bot1", "g1"),
                "24 小时窗口已过——计数归零");
    }

    @Test
    void 话题快照与氛围_覆写读取往返() {
        assertNull(groupStateHash.topicSnapshot("qq", "bot1", "g1"));
        assertNull(groupStateHash.vibe("qq", "bot1", "g1"));

        groupStateHash.updateTopicSnapshot("qq", "bot1", "g1", "{\"topics\":[{\"entryId\":1}]}");
        groupStateHash.updateVibe("qq", "bot1", "g1", "热闹");
        assertEquals("{\"topics\":[{\"entryId\":1}]}", groupStateHash.topicSnapshot("qq", "bot1", "g1"));
        assertEquals("热闹", groupStateHash.vibe("qq", "bot1", "g1"));
    }

    @Test
    void 既有频控字段不受扩容影响_60秒窗口语义保持() {
        groupStateHash.recordBotSend("qq", "bot1", "g1");
        groupStateHash.recordBotSend("qq", "bot1", "g1");
        assertEquals(2, groupStateHash.sendCountInWindow("qq", "bot1", "g1"));

        clock.forward(Duration.ofSeconds(61));
        assertEquals(0, groupStateHash.sendCountInWindow("qq", "bot1", "g1"),
                "既有 60 秒频控窗口语义不变");
    }

    @Test
    void 重建直写路径_整图覆写后读取一致() {
        long now = clock.millis();
        groupStateHash.rewriteLastMsgAt("qq", "bot1", "g1", now - 1000L);
        groupStateHash.rewriteMsgRate("qq", "bot1", "g1", now - 600_000L, 7);
        groupStateHash.rewriteAgentSend24h("qq", "bot1", "g1", now - 3600_000L, 3);
        groupStateHash.rewriteActiveMembers("qq", "bot1", "g1",
                Map.of("userA", now));

        assertEquals(now - 1000L, groupStateHash.lastMsgAt("qq", "bot1", "g1"));
        assertEquals(7, groupStateHash.messageRateCount("qq", "bot1", "g1"));
        assertEquals(3, groupStateHash.agentSend24hCount("qq", "bot1", "g1"));
        assertEquals(1, groupStateHash.activeMembersRecent("qq", "bot1", "g1").size());
    }
}
