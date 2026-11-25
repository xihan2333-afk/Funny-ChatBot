// 守护纪律：群状态六项纯计算零模型、静默读取时扣暂停累计、Redis 失败降级不抛（T050）
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.engine.intake.GroupDO;
import cn.edu.scut.chatbot.engine.intake.GroupMapper;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import cn.edu.scut.chatbot.loop.message.AgentMessage;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.memory.social.GroupStateService;
import cn.edu.scut.chatbot.memory.social.GroupStateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T050：GroupStateService——事件驱动更新、静默扣暂停、重建重放、Redis 降级。
 */
class GroupStateServiceTest {

    private GroupStateHash groupStateHash;
    private GroupMapper groupMapper;
    private AgentSessionMapper sessionMapper;
    private SessionManager sessionManager;
    private MutableClock clock;
    private GroupStateService groupStateService;

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
        groupStateHash = mock(GroupStateHash.class);
        groupMapper = mock(GroupMapper.class);
        sessionMapper = mock(AgentSessionMapper.class);
        sessionManager = mock(SessionManager.class);
        clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
        groupStateService = new GroupStateServiceImpl(
                groupStateHash, groupMapper, sessionMapper, sessionManager, clock);
    }

    @Test
    void 群消息驱动更新_静默时长归零() {
        when(groupStateHash.lastMsgAt("qq", "bot1", "g1")).thenReturn(clock.millis());
        when(groupMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(null);
        when(groupStateHash.messageRateCount("qq", "bot1", "g1")).thenReturn(1);
        when(groupStateHash.activeMembersRecent("qq", "bot1", "g1")).thenReturn(Map.of("userA", 1L));

        groupStateService.onGroupMessage(group, "userA", "在吗");
        verify(groupStateHash).recordGroupMessage(eq("qq"), eq("bot1"), eq("g1"),
                eq("userA"), eq(clock.millis()));

        GroupStateService.GroupStateSnapshot snapshot = groupStateService.read(group);
        assertEquals(0, snapshot.silenceMillis(), "刚收到消息静默归零");
        assertEquals(1, snapshot.msgRatePerHour());
        assertEquals(1, snapshot.activeMembersCount());
    }

    @Test
    void 静默时长读取时扣群暂停累计_暂停期间静默不增长() {
        // lastMsgAt 在 2 小时前；群暂停累计 1 小时 → 有效静默 1 小时
        when(groupStateHash.lastMsgAt("qq", "bot1", "g1")).thenReturn(clock.millis() - Duration.ofHours(2).toMillis());
        GroupDO paused = new GroupDO();
        paused.setPausedAccumulatedMillis(Duration.ofHours(1).toMillis());
        paused.setPausedAt(null);
        when(groupMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(paused);
        stubReadDefaults();

        assertEquals(Duration.ofHours(1).toMillis(), groupStateService.read(group).silenceMillis());
    }

    @Test
    void 进行中暂停段计入顺延() {
        when(groupStateHash.lastMsgAt("qq", "bot1", "g1")).thenReturn(clock.millis() - Duration.ofHours(3).toMillis());
        GroupDO pausing = new GroupDO();
        pausing.setPausedAccumulatedMillis(0L);
        pausing.setPausedAt(LocalDateTime.now(clock).minusHours(1));   // 暂停已持续 1 小时
        when(groupMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(pausing);
        stubReadDefaults();

        assertEquals(Duration.ofHours(2).toMillis(), groupStateService.read(group).silenceMillis(),
                "进行中的暂停段同样顺延计时");
    }

    @Test
    void 从转录重建_24h窗口重放各状态项() {
        AgentSessionDO session = new AgentSessionDO();
        session.setId(1L);
        when(sessionMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(session);
        Instant now = clock.instant();
        List<AgentMessage> transcript = List.of(
                // 30 小时前的消息——出 24h 窗口不计
                userMessage("m0", now.minus(Duration.ofHours(30)), "userOld"),
                // 2 小时前 userA 两条（出 1 小时速率窗、在 24h 窗）
                userMessage("m1", now.minus(Duration.ofHours(2)), "userA"),
                userMessage("m2", now.minus(Duration.ofHours(2)).plusSeconds(10), "userA"),
                // 30 分钟前 userB 一条（在两个窗口内）
                userMessage("m3", now.minus(Duration.ofMinutes(30)), "userB"),
                // Agent 1 小时前两条
                new AssistantMessage("m4", now.minus(Duration.ofHours(1)), 50, null, "回复1", null, "stop", null),
                new AssistantMessage("m5", now.minus(Duration.ofMinutes(50)), 50, null, "回复2", null, "stop", null));
        when(sessionManager.loadTranscript(session)).thenReturn(transcript);

        groupStateService.rebuild(group);

        verify(groupStateHash).rewriteLastMsgAt("qq", "bot1", "g1",
                now.minus(Duration.ofMinutes(30)).toEpochMilli());
        verify(groupStateHash).rewriteMsgRate("qq", "bot1", "g1",
                now.minus(Duration.ofMinutes(30)).toEpochMilli(), 1);
        verify(groupStateHash).rewriteAgentSend24h("qq", "bot1", "g1",
                now.minus(Duration.ofHours(1)).toEpochMilli(), 2);
        verify(groupStateHash).rewriteActiveMembers(eq("qq"), eq("bot1"), eq("g1"), any());
    }

    @Test
    void 会话不存在时重建静默跳过() {
        when(sessionMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(null);
        assertDoesNotThrow(() -> groupStateService.rebuild(group));
        verify(sessionManager, never()).loadTranscript(any());
    }

    @Test
    void Redis读取失败降级_返回空快照不抛() {
        when(groupStateHash.lastMsgAt(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));
        GroupStateService.GroupStateSnapshot snapshot = assertDoesNotThrow(() -> groupStateService.read(group));
        assertEquals(0, snapshot.silenceMillis(), "降级空快照——调度器按不破冰处理");
        assertEquals(0, snapshot.msgRatePerHour());
    }

    @Test
    void Redis写入失败降级_不抛且不影响主链路() {
        Mockito.doThrow(new RuntimeException("redis down"))
                .when(groupStateHash).recordGroupMessage(anyString(), anyString(), anyString(), any(), anyLong());
        assertDoesNotThrow(() -> groupStateService.onGroupMessage(group, "userA", "在吗"));
    }

    @Test
    void Agent发言记账_频控与24h统计双通道() {
        groupStateService.onBotSend(group);
        verify(groupStateHash).recordBotSend("qq", "bot1", "g1");
        verify(groupStateHash).recordAgentSend24h(eq("qq"), eq("bot1"), eq("g1"), anyLong());
    }

    @Test
    void 压缩完成刷新氛围_不触碰话题快照() {
        groupStateService.onCompactionRefreshed(group, "装修", "热闹");
        verify(groupStateHash).updateVibe("qq", "bot1", "g1", "热闹");
        verify(groupStateHash, never()).updateTopicSnapshot(anyString(), anyString(), anyString(), any());
    }

    @Test
    void 读快照拼装_六状态项各就各位() {
        when(groupStateHash.lastMsgAt("qq", "bot1", "g1")).thenReturn(clock.millis() - 600_000L);
        when(groupMapper.selectByGroup("qq", "bot1", "g1")).thenReturn(null);
        when(groupStateHash.messageRateCount("qq", "bot1", "g1")).thenReturn(12);
        when(groupStateHash.topicSnapshot("qq", "bot1", "g1")).thenReturn("{\"topics\":[]}");
        when(groupStateHash.vibe("qq", "bot1", "g1")).thenReturn("热闹");
        when(groupStateHash.agentSend24hCount("qq", "bot1", "g1")).thenReturn(3);
        when(groupStateHash.activeMembersRecent("qq", "bot1", "g1"))
                .thenReturn(Map.of("u1", 1L, "u2", 2L));

        GroupStateService.GroupStateSnapshot snapshot = groupStateService.read(group);
        assertEquals(600_000L, snapshot.silenceMillis());
        assertEquals(12, snapshot.msgRatePerHour());
        assertEquals("{\"topics\":[]}", snapshot.topicSnapshot());
        assertEquals("热闹", snapshot.vibe());
        assertEquals(3, snapshot.agentSend24h());
        assertEquals(2, snapshot.activeMembersCount());
        assertNotNull(snapshot);
    }

    private void stubReadDefaults() {
        when(groupStateHash.messageRateCount("qq", "bot1", "g1")).thenReturn(0);
        when(groupStateHash.topicSnapshot("qq", "bot1", "g1")).thenReturn(null);
        when(groupStateHash.vibe("qq", "bot1", "g1")).thenReturn(null);
        when(groupStateHash.agentSend24hCount("qq", "bot1", "g1")).thenReturn(0);
        when(groupStateHash.activeMembersRecent("qq", "bot1", "g1")).thenReturn(Map.of());
    }

    private static UserMessage userMessage(String messageId, Instant occurredAt, String sender) {
        return new UserMessage(messageId, occurredAt, 100, null, sender, List.of());
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
