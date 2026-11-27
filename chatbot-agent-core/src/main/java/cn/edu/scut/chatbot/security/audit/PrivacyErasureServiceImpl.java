package cn.edu.scut.chatbot.security.audit;

import cn.edu.scut.chatbot.engine.session.AgentSessionMapper;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.management.PrivacyErasureService;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.profile.MemberRelationMapper;
import cn.edu.scut.chatbot.memory.profile.UserProfileMapper;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PrivacyErasureService} 实现（T103，FR-024）：
 * 四路同步清除（memory_entry + Qdrant 同 id 点删除、user_profile、member_relation、
 * transcript_entry 该用户消息）+ 严格按三元组保护跨群同 userId 数据 + security_event(adminOperate) 审计。
 *
 * <p>位于 security/audit 包而非 management 包，避免 management 包触达 engine/memory 内部 Mapper
 * （满足 ArchUnit management 收口规约）。
 */
@Service
public class PrivacyErasureServiceImpl implements PrivacyErasureService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyErasureServiceImpl.class);

    private final MemoryEntryMapper memoryEntryMapper;
    private final UserProfileMapper userProfileMapper;
    private final MemberRelationMapper memberRelationMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final QdrantClient qdrantClient;
    private final SecurityEventRecorder securityEventRecorder;

    public PrivacyErasureServiceImpl(MemoryEntryMapper memoryEntryMapper,
                                     UserProfileMapper userProfileMapper,
                                     MemberRelationMapper memberRelationMapper,
                                     AgentSessionMapper agentSessionMapper,
                                     Optional<QdrantClient> qdrantClient,
                                     SecurityEventRecorder securityEventRecorder) {
        this.memoryEntryMapper = memoryEntryMapper;
        this.userProfileMapper = userProfileMapper;
        this.memberRelationMapper = memberRelationMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.qdrantClient = qdrantClient.orElse(null);
        this.securityEventRecorder = securityEventRecorder;
    }

    @Override
    @Transactional
    public ErasureReport erase(String platform, String botId, String groupId, String userId, String operator) {
        requireNonBlank(platform, "platform");
        requireNonBlank(botId, "botId");
        requireNonBlank(groupId, "groupId");
        requireNonBlank(userId, "userId");

        // 1. memory_entry + Qdrant 同 id 点同步删除（先查本群该用户条目 id，再两库同删）
        List<Long> memoryIds = memoryEntryMapper.selectIdsByGroupAndUser(platform, botId, groupId, userId);
        int deletedMemories = 0;
        int deletedVectorPoints = 0;
        if (memoryIds != null && !memoryIds.isEmpty()) {
            deletedMemories = memoryEntryMapper.deleteByIds(memoryIds);
            if (qdrantClient != null) {
                qdrantClient.delete(MemoryIndexPoints.COLLECTION_ALIAS, memoryIds);
                deletedVectorPoints = memoryIds.size();
            }
        }

        // 2. user_profile（限定本群三元组 + userId）
        int deletedProfiles = userProfileMapper.deleteByGroupAndUser(platform, botId, groupId, userId);

        // 3. member_relation（限定本群三元组 + user_id_a/user_id_b 任一命中）
        int deletedRelations = memberRelationMapper.deleteByGroupAndUser(platform, botId, groupId, userId);

        // 4. transcript_entry（限定本群三元组 + kind='user' + senderUserId）
        int deletedTranscripts = agentSessionMapper.deleteUserTranscriptByGroup(platform, botId, groupId, userId);

        ErasureReport report = new ErasureReport(
                platform, botId, groupId, userId,
                deletedMemories, deletedVectorPoints, deletedProfiles, deletedRelations, deletedTranscripts);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "privacyIndividualErasure");
        detail.put("operator", (operator == null || operator.isBlank()) ? "admin" : operator);
        detail.put("platform", platform);
        detail.put("botId", botId);
        detail.put("groupId", groupId);
        detail.put("userId", userId);
        detail.put("deletedMemories", deletedMemories);
        detail.put("deletedVectorPoints", deletedVectorPoints);
        detail.put("deletedProfiles", deletedProfiles);
        detail.put("deletedRelations", deletedRelations);
        detail.put("deletedTranscripts", deletedTranscripts);
        securityEventRecorder.record(SecurityEventKind.ADMIN_OPERATE,
                SecurityDims.ofUser(platform, botId, groupId, userId), detail, null);

        log.info("个体数据清除完成 group={}:{}:{} userId={} memories={} vectors={} profiles={} relations={} transcripts={}",
                platform, botId, groupId, userId,
                deletedMemories, deletedVectorPoints, deletedProfiles, deletedRelations, deletedTranscripts);
        return report;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
