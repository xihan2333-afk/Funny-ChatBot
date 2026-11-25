// 守护纪律：话题状态机由摘要段驱动零模型、再热轻量匹配唤醒、closed 不再唤醒（T054）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.engine.task.TaskWakeScanner;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.social.TopicLifecycleService;
import cn.edu.scut.chatbot.memory.social.TopicLifecycleServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T054：TopicLifecycleService——hot → cooling → closed 状态机与再热唤醒。
 */
class TopicLifecycleTest {

    private MemoryEntryMapper memoryEntryMapper;
    private GroupStateHash groupStateHash;
    private TaskWakeScanner taskWakeScanner;
    private SystemParamService systemParams;
    private MutableClock clock;
    private TopicLifecycleService topicLifecycle;

    private final GroupKey group = new GroupKey("qq", "bot1", "g1");

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
        memoryEntryMapper = mock(MemoryEntryMapper.class);
        groupStateHash = mock(GroupStateHash.class);
        taskWakeScanner = mock(TaskWakeScanner.class);
        systemParams = mock(SystemParamService.class);
        when(systemParams.getString(anyString(), any())).thenReturn(null);
        // 有状态快照桩：写回后可再读（连续两次压缩的计次延续）
        java.util.concurrent.atomic.AtomicReference<String> snapshotStore =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(groupStateHash.topicSnapshot(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> snapshotStore.get());
        org.mockito.Mockito.doAnswer(inv -> {
            snapshotStore.set(inv.getArgument(3));
            return null;
        }).when(groupStateHash).updateTopicSnapshot(anyString(), anyString(), anyString(), any());
        clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
        topicLifecycle = new TopicLifecycleServiceImpl(
                memoryEntryMapper, groupStateHash, taskWakeScanner, systemParams, clock);
    }

    private MemoryEntryDO topicEntry(long id, String keywordsJson) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setMemoryType(MemoryEntryDO.TYPE_TOPIC);
        entry.setKeywords(keywordsJson);
        entry.setTopicLifecycle(MemoryEntryDO.TOPIC_HOT);
        return entry;
    }

    @Test
    void 压缩话题段命中_热度加一计次清零() {
        when(memoryEntryMapper.selectTopicByLifecycle("qq", "bot1", "g1", "hot"))
                .thenReturn(List.of(topicEntry(1L, "[\"装修\",\"预算\"]")));

        topicLifecycle.onCompactionRefreshed(group, List.of("最近在聊装修的事"));

        verify(memoryEntryMapper, never()).updateTopicLifecycle(anyLong(), anyString());
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(groupStateHash).updateTopicSnapshot(eq("qq"), eq("bot1"), eq("g1"), snapshot.capture());
        try {
            JsonNode topics = JsonMappers.shared().readTree(snapshot.getValue()).path("topics");
            assertEquals(1, topics.size());
            assertEquals(1, topics.get(0).path("count").asInt());
            assertEquals(0, topics.get(0).path("misses").asInt());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void 连续两次压缩不含_热话题迁移cooling() {
        when(memoryEntryMapper.selectTopicByLifecycle("qq", "bot1", "g1", "hot"))
                .thenReturn(List.of(topicEntry(1L, "[\"装修\",\"预算\"]")));

        topicLifecycle.onCompactionRefreshed(group, List.of("聊了别的"));   // 第一次未含
        verify(memoryEntryMapper, never()).updateTopicLifecycle(anyLong(), anyString());

        topicLifecycle.onCompactionRefreshed(group, List.of("聊了别的"));   // 第二次未含 → cooling
        verify(memoryEntryMapper).updateTopicLifecycle(1L, MemoryEntryDO.TOPIC_COOLING);
    }

    @Test
    void 冷却话题关键词在群消息再现_回hot并唤醒Task扫描() {
        MemoryEntryDO cooling = topicEntry(1L, "[\"预算\",\"瓷砖\"]");
        cooling.setTopicLifecycle(MemoryEntryDO.TOPIC_COOLING);
        when(memoryEntryMapper.selectTopicByLifecycle("qq", "bot1", "g1", "cooling"))
                .thenReturn(List.of(cooling));

        topicLifecycle.onGroupMessage(group, "这批瓷砖预算超了，得重新算");

        verify(memoryEntryMapper).updateTopicLifecycle(1L, MemoryEntryDO.TOPIC_HOT);
        verify(taskWakeScanner).scanWaitingTasks();
    }

    @Test
    void 消息不含冷却关键词_零动作零唤醒() {
        MemoryEntryDO cooling = topicEntry(1L, "[\"预算\",\"瓷砖\"]");
        cooling.setTopicLifecycle(MemoryEntryDO.TOPIC_COOLING);
        when(memoryEntryMapper.selectTopicByLifecycle("qq", "bot1", "g1", "cooling"))
                .thenReturn(List.of(cooling));

        topicLifecycle.onGroupMessage(group, "今天天气不错");

        verify(memoryEntryMapper, never()).updateTopicLifecycle(anyLong(), anyString());
        verify(taskWakeScanner, never()).scanWaitingTasks();
    }

    @Test
    void closed条目不在再热查询面_结构性不再唤醒() {
        // selectTopicByLifecycle(cooling) 不含 closed——closed 翻篇不再唤醒是结构保证
        when(memoryEntryMapper.selectTopicByLifecycle("qq", "bot1", "g1", "cooling"))
                .thenReturn(List.of());

        topicLifecycle.onGroupMessage(group, "聊装修预算");

        verify(memoryEntryMapper, never()).updateTopicLifecycle(anyLong(), anyString());
        verify(taskWakeScanner, never()).scanWaitingTasks();
    }

    @Test
    void 冷却超三十天_日批兜底置closed() {
        MemoryEntryDO cooling = topicEntry(1L, "[\"装修\"]");
        cooling.setTopicLifecycle(MemoryEntryDO.TOPIC_COOLING);
        // MySQL NOW 是 UTC、业务时钟 UTC+8——updatedAt 按库回读值（比业务早 8 小时）更保守
        cooling.setUpdatedAt(LocalDateTime.now(clock).minus(Duration.ofDays(31)));
        when(memoryEntryMapper.selectActiveGroups()).thenReturn(List.of(groupRow()));
        when(memoryEntryMapper.selectTopicByLifecycle("qq", "bot1", "g1", "cooling"))
                .thenReturn(List.of(cooling));

        topicLifecycle.runDailyCloseSweep();

        verify(memoryEntryMapper).updateTopicLifecycle(1L, MemoryEntryDO.TOPIC_CLOSED);
    }

    @Test
    void 冷却未满期_日批兜底不动() {
        MemoryEntryDO cooling = topicEntry(1L, "[\"装修\"]");
        cooling.setTopicLifecycle(MemoryEntryDO.TOPIC_COOLING);
        cooling.setUpdatedAt(LocalDateTime.now(clock).minus(Duration.ofDays(10)));
        when(memoryEntryMapper.selectActiveGroups()).thenReturn(List.of(groupRow()));
        when(memoryEntryMapper.selectTopicByLifecycle("qq", "bot1", "g1", "cooling"))
                .thenReturn(List.of(cooling));

        topicLifecycle.runDailyCloseSweep();

        verify(memoryEntryMapper, never()).updateTopicLifecycle(anyLong(), anyString());
    }

    private cn.edu.scut.chatbot.memory.entry.MemoryGroupDO groupRow() {
        cn.edu.scut.chatbot.memory.entry.MemoryGroupDO row =
                new cn.edu.scut.chatbot.memory.entry.MemoryGroupDO();
        row.setPlatform("qq");
        row.setBotId("bot1");
        row.setGroupId("g1");
        return row;
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
