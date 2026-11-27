// 守护纪律：个体数据四路同步清除（memory + Qdrant + profile + relation + transcript）与审计（admin-auth 契约第 4 节）
package cn.edu.scut.chatbot.security.audit;

import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.management.PrivacyErasureService;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.profile.MemberRelationMapper;
import cn.edu.scut.chatbot.memory.profile.UserProfileMapper;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrivacyErasureServiceTest {

    @Test
    void 四路同步清除并联动Qdrant删点与管理审计() {
        MemoryEntryMapper memoryMapper = mock(MemoryEntryMapper.class);
        UserProfileMapper profileMapper = mock(UserProfileMapper.class);
        MemberRelationMapper relationMapper = mock(MemberRelationMapper.class);
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);

        when(memoryMapper.selectIdsByGroupAndUser("qq", "b1", "g1", "u1")).thenReturn(List.of(101L, 102L));
        when(memoryMapper.deleteByIds(List.of(101L, 102L))).thenReturn(2);
        when(profileMapper.deleteByGroupAndUser("qq", "b1", "g1", "u1")).thenReturn(1);
        when(relationMapper.deleteByGroupAndUser("qq", "b1", "g1", "u1")).thenReturn(3);
        when(sessionMapper.deleteUserTranscriptByGroup("qq", "b1", "g1", "u1")).thenReturn(4);

        PrivacyErasureServiceImpl service = new PrivacyErasureServiceImpl(
                memoryMapper, profileMapper, relationMapper, sessionMapper,
                Optional.of(qdrant), recorder);

        PrivacyErasureService.ErasureReport report = service.erase("qq", "b1", "g1", "u1", "ops");

        assertEquals(2, report.deletedMemories());
        assertEquals(2, report.deletedVectorPoints());
        assertEquals(1, report.deletedProfiles());
        assertEquals(3, report.deletedRelations());
        assertEquals(4, report.deletedTranscripts());

        verify(qdrant).delete(MemoryIndexPoints.COLLECTION_ALIAS, List.of(101L, 102L));
        verify(recorder).record(eq(SecurityEventKind.ADMIN_OPERATE),
                eq(SecurityDims.ofUser("qq", "b1", "g1", "u1")),
                argThat(d -> "privacyIndividualErasure".equals(d.get("action"))
                        && Integer.valueOf(2).equals(d.get("deletedMemories"))),
                isNull());
    }

    @Test
    void 无记忆条目时不触发Qdrant删除且参数缺失直接拒绝() {
        MemoryEntryMapper memoryMapper = mock(MemoryEntryMapper.class);
        UserProfileMapper profileMapper = mock(UserProfileMapper.class);
        MemberRelationMapper relationMapper = mock(MemberRelationMapper.class);
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        QdrantClient qdrant = mock(QdrantClient.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);

        when(memoryMapper.selectIdsByGroupAndUser("qq", "b1", "g1", "u2")).thenReturn(List.of());
        when(profileMapper.deleteByGroupAndUser("qq", "b1", "g1", "u2")).thenReturn(0);
        when(relationMapper.deleteByGroupAndUser("qq", "b1", "g1", "u2")).thenReturn(0);
        when(sessionMapper.deleteUserTranscriptByGroup("qq", "b1", "g1", "u2")).thenReturn(1);

        PrivacyErasureServiceImpl service = new PrivacyErasureServiceImpl(
                memoryMapper, profileMapper, relationMapper, sessionMapper,
                Optional.of(qdrant), recorder);

        PrivacyErasureService.ErasureReport report = service.erase("qq", "b1", "g1", "u2", null);
        assertEquals(0, report.deletedMemories());
        assertEquals(0, report.deletedVectorPoints());
        assertEquals(1, report.deletedTranscripts());
        verify(memoryMapper, never()).deleteByIds(any());
        verify(qdrant, never()).delete(anyString(), anyList());

        assertThrows(IllegalArgumentException.class, () -> service.erase(" ", "b1", "g1", "u2", "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.erase("qq", "b1", "", "u2", "ops"));
        assertThrows(IllegalArgumentException.class, () -> service.erase("qq", "b1", "g1", null, "ops"));
    }
}
