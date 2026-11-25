// 守护纪律：统计全程纯计算零模型调用（不触模型网关）、活跃度分档边界精确、
// 群体画像聚合口径（活跃时段/话题分布/成员概况）、关系边字典序归一与强度累计
package cn.edu.scut.chatbot.memory;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.profile.*;
import cn.edu.scut.chatbot.memory.sediment.SedimentTranscriptDO;
import cn.edu.scut.chatbot.memory.sediment.SedimentTranscriptMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyProfileStatisticsTest {

    private static final GroupKey GROUP = new GroupKey("qq", "10000", "98001");

    private static final Instant NOW = Instant.parse("2026-11-25T04:00:00Z");

    private UserProfileMapper profileMapper;
    private GroupProfileMapper groupProfileMapper;
    private MemberRelationMapper relationMapper;
    private MemoryEntryMapper entryMapper;
    private SedimentTranscriptMapper transcriptMapper;
    private DailyProfileStatistics statistics;

    @BeforeEach
    void setUp() {
        profileMapper = mock(UserProfileMapper.class);
        groupProfileMapper = mock(GroupProfileMapper.class);
        relationMapper = mock(MemberRelationMapper.class);
        entryMapper = mock(MemoryEntryMapper.class);
        transcriptMapper = mock(SedimentTranscriptMapper.class);
        SystemParamService systemParams = mock(SystemParamService.class);
        statistics = new DailyProfileStatistics(profileMapper, groupProfileMapper,
                relationMapper, entryMapper, transcriptMapper, systemParams,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(systemParams.getString(anyString(), any())).thenReturn(null);
        when(entryMapper.selectActiveByGroup("qq", "10000", "98001")).thenReturn(List.of());
    }

    private SedimentTranscriptDO message(String sender, int hourOffset, String payloadJson) {
        SedimentTranscriptDO entry = new SedimentTranscriptDO();
        entry.setId(System.nanoTime());
        entry.setKind(SedimentTranscriptDO.KIND_USER);
        entry.setPayload(payloadJson.replace("{SENDER}", sender));
        entry.setOccurredAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(hourOffset));
        return entry;
    }

    private static String userPayload(String... atTargets) {
        StringBuilder parts = new StringBuilder();
        for (String target : atTargets) {
            if (parts.length() > 0) {
                parts.append(',');
            }
            parts.append("{\"part\":\"at\",\"targetUserId\":\"").append(target).append("\"}");
        }
        if (parts.length() > 0) {
            parts.append(',');
        }
        parts.append("{\"part\":\"text\",\"text\":\"内容\"}");
        return "{\"senderUserId\":\"{SENDER}\",\"parts\":[" + parts + "]}";
    }

    // ===== 活跃度分档边界 =====

    @Test
    void 活跃度分档边界_二十与五是分界() {
        assertThat(DailyProfileStatistics.activityLevelOf(20)).isEqualTo("活跃");
        assertThat(DailyProfileStatistics.activityLevelOf(19)).isEqualTo("一般");
        assertThat(DailyProfileStatistics.activityLevelOf(5)).isEqualTo("一般");
        assertThat(DailyProfileStatistics.activityLevelOf(4)).isEqualTo("安静");
        assertThat(DailyProfileStatistics.activityLevelOf(1)).isEqualTo("安静");
    }

    @Test
    void 氛围基线_按日消息量三档() {
        assertThat(DailyProfileStatistics.vibeOf(200)).isEqualTo("热闹");
        assertThat(DailyProfileStatistics.vibeOf(199)).isEqualTo("一般");
        assertThat(DailyProfileStatistics.vibeOf(50)).isEqualTo("一般");
        assertThat(DailyProfileStatistics.vibeOf(49)).isEqualTo("安静");
    }

    // ===== 群体画像聚合口径 =====

    @Test
    void 群体画像_活跃时段成员概况氛围一体刷新_话题分布来自topic记忆() {
        when(transcriptMapper.selectUserSince(eq("qq"), eq("10000"), eq("98001"),
                any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(
                        message("u1", 1, userPayload()),
                        message("u1", 2, userPayload()),
                        message("u2", 3, userPayload())));
        MemoryEntryDO topic = new MemoryEntryDO();
        topic.setMemoryType(MemoryEntryDO.TYPE_TOPIC);
        topic.setContent("装修");
        when(entryMapper.selectActiveByGroup("qq", "10000", "98001")).thenReturn(List.of(topic));

        statistics.refreshForGroup(GROUP);

        ArgumentCaptor<GroupProfileDO> profile = ArgumentCaptor.forClass(GroupProfileDO.class);
        verify(groupProfileMapper).upsertProfile(profile.capture());
        // 活跃时段：NOW 是 04:00 UTC，三条消息分别落在 03/02/01 点
        assertThat(profile.getValue().getActiveHours()).contains("\"3\":1").contains("\"1\":1");
        // 成员概况：按发言数排序（u1 两票在前）
        assertThat(profile.getValue().getActiveMembers()).contains("\"u1\":2").contains("\"u2\":1");
        // 氛围：3 条消息 → 安静
        assertThat(profile.getValue().getVibeBaseline()).isEqualTo("安静");
        // 话题分布：topic 类记忆正文计数
        assertThat(profile.getValue().getTopicDist()).contains("装修");
    }

    // ===== 关系累计 =====

    @Test
    void 成员关系_消息内艾特群友累计interact边_字典序归一无向唯一() {
        when(transcriptMapper.selectUserSince(eq("qq"), eq("10000"), eq("98001"),
                any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(
                        // u9 @ u2 两次（同一条消息 @ 一次 + 另一条再 @）
                        message("u9", 1, userPayload("u2")),
                        message("u9", 2, userPayload("u2")),
                        // u2 @ u9 一次——无向边与上面同一条
                        message("u2", 3, userPayload("u9"))));

        statistics.refreshForGroup(GROUP);

        ArgumentCaptor<MemberRelationDO> relation = ArgumentCaptor.forClass(MemberRelationDO.class);
        verify(relationMapper, org.mockito.Mockito.times(2)).upsertRelation(relation.capture());
        // 字典序归一：u2 < u9 → a=u2 b=u9；强度按发送方向分两次累计（3 与 1）
        List<MemberRelationDO> captured = relation.getAllValues();
        assertThat(captured).allSatisfy(edge -> {
            assertThat(edge.getUserIdA()).isEqualTo("u2");
            assertThat(edge.getUserIdB()).isEqualTo("u9");
            assertThat(edge.getRelationKind()).isEqualTo("interact");
        });
        assertThat(captured).extracting(MemberRelationDO::getStrength)
                .containsExactlyInAnyOrder(2, 1);
    }

    @Test
    void 活跃度刷新_按当日发言数落档_只动已有画像行() {
        when(transcriptMapper.selectUserSince(eq("qq"), eq("10000"), eq("98001"),
                any(LocalDateTime.class), anyInt()))
                .thenReturn(java.util.stream.IntStream.rangeClosed(0, 19)
                        .mapToObj(i -> message("u1", 1, userPayload()))
                        .toList());
        UserProfileDO profile = new UserProfileDO();
        profile.setId(7L);
        when(profileMapper.selectByGroupAndUser("qq", "10000", "98001", "u1")).thenReturn(profile);
        when(profileMapper.selectByGroupAndUser("qq", "10000", "98001", "u2")).thenReturn(null);

        statistics.refreshForGroup(GROUP);

        // 20 条 → 活跃；其余字段 null（COALESCE 不覆盖）；无画像行的 u2 不建行
        verify(profileMapper).updateFields(eq(7L), isNull(), isNull(), isNull(),
                eq("活跃"), isNull());
        verify(profileMapper, org.mockito.Mockito.never())
                .updateFields(eq(0L), any(), any(), any(), any(), any());
    }
}
