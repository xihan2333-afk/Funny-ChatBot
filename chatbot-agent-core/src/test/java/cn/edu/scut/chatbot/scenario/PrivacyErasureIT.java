// 守护纪律（US12 集成验收场景 1/2，admin-auth 契约第 4 节）：按群 + userId 清除后
// memory_entry / Qdrant 向量 / user_profile / member_relation / transcript_entry 四路零命中，
// 同 userId 在其它群的数据完整保留（跨群保护负向断言），清除动作记入 security_event(adminOperate)。
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.infrastructure.qdrant.QdrantClient;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.management.PrivacyErasureService;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.profile.MemberRelationDO;
import cn.edu.scut.chatbot.memory.profile.MemberRelationMapper;
import cn.edu.scut.chatbot.memory.profile.UserProfileDO;
import cn.edu.scut.chatbot.memory.profile.UserProfileMapper;
import cn.edu.scut.chatbot.memory.retrieve.CollectionGuard;
import cn.edu.scut.chatbot.memory.retrieve.MemoryIndexPoints;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 个体隐私数据四路清除 IT（Testcontainers MySQL + Qdrant，spec US12 验收场景 1/2）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivacyErasureIT extends AbstractIntegrationTest {

    private static final String PLATFORM = "qq";
    private static final String BOT = "bot-us12";
    private static final String GROUP_TARGET = "grp-us12-target";
    private static final String GROUP_OTHER = "grp-us12-other";
    private static final String USER_ID = "u-us12-alice";

    @Autowired
    private PrivacyErasureService privacyErasureService;

    @Autowired
    private MemoryEntryMapper memoryEntryMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private MemberRelationMapper memberRelationMapper;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private CollectionGuard collectionGuard;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM memory_entry WHERE group_id IN (?, ?)", GROUP_TARGET, GROUP_OTHER);
        jdbcTemplate.update("DELETE FROM user_profile WHERE group_id IN (?, ?)", GROUP_TARGET, GROUP_OTHER);
        jdbcTemplate.update("DELETE FROM member_relation WHERE group_id IN (?, ?)", GROUP_TARGET, GROUP_OTHER);
        jdbcTemplate.update("DELETE FROM transcript_entry WHERE group_id IN (?, ?)", GROUP_TARGET, GROUP_OTHER);
    }

    @Test
    void 清除目标群内指定用户四路数据_同用户其它群数据不受影响且记审计() {
        collectionGuard.ensureCollection();
        QdrantClient.QdrantCollectionInfo info = qdrantClient.describeCollection(MemoryIndexPoints.COLLECTION_ALIAS);
        if (info == null) {
            qdrantClient.createCollection(MemoryIndexPoints.COLLECTION_ALIAS, 8, MemoryIndexPoints.STABLE_PAYLOAD_INDEXES);
            info = qdrantClient.describeCollection(MemoryIndexPoints.COLLECTION_ALIAS);
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        long targetMemId = 9120001L;
        long otherMemId = 9120002L;

        // 1. 写入目标群与对照群两份完全同 userId 的记忆 + Qdrant 向量
        MemoryEntryDO targetMem = newMemory(targetMemId, GROUP_TARGET, "爱丽丝在目标群喜欢喝美式", now);
        MemoryEntryDO otherMem = newMemory(otherMemId, GROUP_OTHER, "爱丽丝在另一群喜欢打网球", now);
        memoryEntryMapper.insertEntry(targetMem);
        memoryEntryMapper.insertEntry(otherMem);

        int dim = info.dim();
        float[] vec = new float[dim];
        vec[0] = 1.0f;
        qdrantClient.upsert(MemoryIndexPoints.COLLECTION_ALIAS, List.of(
                MemoryIndexPoints.pointOf(targetMem, vec, ZoneId.of("Asia/Shanghai")),
                MemoryIndexPoints.pointOf(otherMem, vec, ZoneId.of("Asia/Shanghai"))));

        // 2. 画像 + 关系 + 转录（目标群与对照群各一份）
        insertProfile(GROUP_TARGET);
        insertProfile(GROUP_OTHER);
        insertRelation(GROUP_TARGET);
        insertRelation(GROUP_OTHER);

        AgentSessionDO sTarget = sessionManager.getOrCreateSession(PLATFORM, BOT, GROUP_TARGET);
        AgentSessionDO sOther = sessionManager.getOrCreateSession(PLATFORM, BOT, GROUP_OTHER);
        sessionManager.appendTranscript(sTarget, List.of(
                new UserMessage("t-us12-1", Instant.now(), 50, null, USER_ID,
                        List.of(new TextPart(TextPart.TYPE_TEXT, "我在目标群发的隐私话")))), null);
        sessionManager.appendTranscript(sOther, List.of(
                new UserMessage("t-us12-2", Instant.now(), 50, null, USER_ID,
                        List.of(new TextPart(TextPart.TYPE_TEXT, "我在对照群发的正常话")))), null);

        // 执行目标群清除
        PrivacyErasureService.ErasureReport report = privacyErasureService.erase(
                PLATFORM, BOT, GROUP_TARGET, USER_ID, "privacy-admin");

        assertThat(report.deletedMemories()).isEqualTo(1);
        assertThat(report.deletedVectorPoints()).isEqualTo(1);
        assertThat(report.deletedProfiles()).isEqualTo(1);
        assertThat(report.deletedRelations()).isEqualTo(1);
        assertThat(report.deletedTranscripts()).isGreaterThanOrEqualTo(1);

        // 四路负向断言：目标群全部零命中
        assertThat(memoryEntryMapper.selectById(targetMemId)).isNull();
        assertThat(userProfileMapper.selectByGroupAndUser(PLATFORM, BOT, GROUP_TARGET, USER_ID)).isNull();
        assertThat(memberRelationMapper.selectByGroup(PLATFORM, BOT, GROUP_TARGET)).isEmpty();
        Integer targetTranscripts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transcript_entry WHERE group_id = ? AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.senderUserId')) = ?",
                Integer.class, GROUP_TARGET, USER_ID);
        assertThat(targetTranscripts).isEqualTo(0);

        List<QdrantClient.QdrantHit> targetHits = qdrantClient.search(
                MemoryIndexPoints.COLLECTION_ALIAS, vec,
                new QdrantClient.QdrantFilter(List.of(
                        new QdrantClient.QdrantFilter.KeywordCondition("groupId", GROUP_TARGET),
                        new QdrantClient.QdrantFilter.KeywordCondition("userId", USER_ID)), List.of()),
                10);
        assertThat(targetHits).isEmpty();

        // 跨群保护负向断言：对照群同 userId 四路全部完整保留
        assertThat(memoryEntryMapper.selectById(otherMemId)).isNotNull();
        assertThat(userProfileMapper.selectByGroupAndUser(PLATFORM, BOT, GROUP_OTHER, USER_ID)).isNotNull();
        assertThat(memberRelationMapper.selectByGroup(PLATFORM, BOT, GROUP_OTHER)).isNotEmpty();
        List<QdrantClient.QdrantHit> otherHits = qdrantClient.search(
                MemoryIndexPoints.COLLECTION_ALIAS, vec,
                new QdrantClient.QdrantFilter(List.of(
                        new QdrantClient.QdrantFilter.KeywordCondition("groupId", GROUP_OTHER),
                        new QdrantClient.QdrantFilter.KeywordCondition("userId", USER_ID)), List.of()),
                10);
        assertThat(otherHits).extracting(QdrantClient.QdrantHit::id).contains(otherMemId);

        // 审计事件留痕
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security_event WHERE event_kind = 'adminOperate'"
                        + " AND group_id = ? AND user_id = ? AND detail LIKE '%privacyIndividualErasure%'",
                Integer.class, GROUP_TARGET, USER_ID);
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    private MemoryEntryDO newMemory(long id, String groupId, String content, LocalDateTime now) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setPlatform(PLATFORM);
        entry.setBotId(BOT);
        entry.setGroupId(groupId);
        entry.setMemoryType("person_fact");
        entry.setContent(content);
        entry.setUserId(USER_ID);
        entry.setOccurredAt(now);
        entry.setSedimentAt(now);
        entry.setStrength(80);
        entry.setLastAccessedAt(now);
        entry.setStatus("active");
        return entry;
    }

    private void insertProfile(String groupId) {
        UserProfileDO p = new UserProfileDO();
        p.setPlatform(PLATFORM);
        p.setBotId(BOT);
        p.setGroupId(groupId);
        p.setUserId(USER_ID);
        p.setPlatformName("Alice");
        p.setFamiliarity(30);
        p.setAffinity(20);
        userProfileMapper.upsertProfile(p);
    }

    private void insertRelation(String groupId) {
        MemberRelationDO rel = new MemberRelationDO();
        rel.setPlatform(PLATFORM);
        rel.setBotId(BOT);
        rel.setGroupId(groupId);
        rel.setUserIdA(USER_ID);
        rel.setUserIdB("u-us12-bob");
        rel.setRelationKind("friend");
        rel.setStrength(50);
        memberRelationMapper.upsertRelation(rel);
    }
}
