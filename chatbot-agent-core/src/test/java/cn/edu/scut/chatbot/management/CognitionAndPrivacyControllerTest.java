package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.engine.session.TranscriptEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryAdminService;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.entry.MemoryLifecycleService;
import cn.edu.scut.chatbot.memory.profile.*;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobDO;
import cn.edu.scut.chatbot.memory.sediment.SedimentJobMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CognitionAndPrivacyControllerTest {

    @Test
    void memoryDetailResolvesSedimentJobAndTranscriptRangeAndSupportsReviseAndDelete() {
        MemoryEntryMapper memoryEntryMapper = mock(MemoryEntryMapper.class);
        MemoryLifecycleService lifecycleService = mock(MemoryLifecycleService.class);
        SedimentJobMapper sedimentJobMapper = mock(SedimentJobMapper.class);
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T06:00:00Z"), ZoneId.of("Asia/Shanghai"));

        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(501L);
        entry.setPlatform("onebot-v11");
        entry.setBotId("bot1");
        entry.setGroupId("g1");
        entry.setMemoryType("PREFERENCE");
        entry.setContent("喜欢喝冰美式");
        entry.setUserId("u1");
        entry.setStrength(8);
        entry.setStatus("ACTIVE");
        entry.setSourceRef("{\"sedimentJobId\": 701}");
        entry.setOccurredAt(LocalDateTime.of(2026, 11, 27, 10, 0));
        when(memoryEntryMapper.selectById(501L)).thenReturn(entry);

        SedimentJobDO job = new SedimentJobDO();
        job.setId(701L);
        job.setStatus("done");
        job.setPayload("{\"sessionId\": 901, \"startEntryId\": 10, \"endEntryId\": 20}");
        job.setCreatedAt(LocalDateTime.of(2026, 11, 27, 10, 5));
        when(sedimentJobMapper.selectById(701L)).thenReturn(job);

        TranscriptEntryDO t1 = new TranscriptEntryDO();
        t1.setId(15L);
        t1.setKind("user");
        t1.setPayload("{\"text\":\"我平时最喜欢冰美式\"}");
        t1.setOccurredAt(LocalDateTime.of(2026, 11, 27, 9, 58));
        TranscriptEntryDO tOutside = new TranscriptEntryDO();
        tOutside.setId(99L);
        tOutside.setKind("user");
        tOutside.setPayload("{}");
        when(sessionMapper.selectOrdered(901L)).thenReturn(List.of(t1, tOutside));

        MemoryAdminService service = new MemoryAdminService(
                memoryEntryMapper, lifecycleService, sedimentJobMapper, sessionMapper, clock);

        MemoryAdminService.MemoryDetailView detail = service.getDetail(501L);
        assertThat(detail.sedimentJob()).isNotNull();
        assertThat(detail.sedimentJob().id()).isEqualTo(701L);
        assertThat(detail.transcriptEntries()).hasSize(1);
        assertThat(detail.transcriptEntries().get(0).id()).isEqualTo(15L);

        GroupKey group = new GroupKey("onebot-v11", "bot1", "g1");
        service.reviseContent(501L, "喜欢喝热拿铁");
        verify(lifecycleService).reviseContent(group, 501L, "喜欢喝热拿铁");

        service.deleteMemory(501L);
        verify(lifecycleService).deleteMemory(group, 501L);
    }

    @Test
    void profileAdminComputesEffectiveAffinityViaAffinityServiceView() {
        UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
        GroupProfileMapper groupProfileMapper = mock(GroupProfileMapper.class);
        MemberRelationMapper memberRelationMapper = mock(MemberRelationMapper.class);
        AffinityService affinityService = mock(AffinityService.class);

        UserProfileDO u = new UserProfileDO();
        u.setId(1L);
        u.setUserId("u1");
        u.setAffinity(80);
        u.setFamiliarity(45);
        when(userProfileMapper.selectByGroup("onebot-v11", "bot1", "g1")).thenReturn(List.of(u));
        when(affinityService.view(new GroupKey("onebot-v11", "bot1", "g1"), "u1"))
                .thenReturn(new AffinityService.AttitudeView(40, AffinityService.Tier.FRIEND, 45, null, null, null));

        ProfileAdminService service = new ProfileAdminService(
                userProfileMapper, groupProfileMapper, memberRelationMapper, affinityService);
        PageResult<ProfileAdminService.UserProfileView> page = service.listUserProfiles("onebot-v11", "bot1", "g1", 1, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).rawAffinity()).isEqualTo(80);
        assertThat(page.items().get(0).effectiveAffinity()).isEqualTo(40);
    }
}
