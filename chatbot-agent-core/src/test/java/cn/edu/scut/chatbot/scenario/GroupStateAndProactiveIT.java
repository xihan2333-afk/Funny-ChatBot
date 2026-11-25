// 守护纪律：六状态项纯计算零模型调用（llm_call_log 无新增）、破冰内部触发照常过
// L0/L1 闸门不设旁路（Decision 留档）、活跃时段外不触发、间隔与预算双限、强负面预算
// 减半、速率窗口读时衰减、Redis 群状态可从转录重建、话题状态机 hot→cooling→closed
// 与关键词再热、Mandate 清单外类别创建即失格（quickstart 场景 F；
// contracts/social-and-supply.md §1/§4、sediment-pipeline.md §6）
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.engine.intake.TriggerEventDO;
import cn.edu.scut.chatbot.engine.scheduler.AutonomyScheduler;
import cn.edu.scut.chatbot.engine.session.AgentSessionDO;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.engine.task.MandateGuard;
import cn.edu.scut.chatbot.engine.task.TaskDO;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.infrastructure.redis.GroupStateHash;
import cn.edu.scut.chatbot.loop.message.AssistantMessage;
import cn.edu.scut.chatbot.loop.message.UserMessage;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryDO;
import cn.edu.scut.chatbot.memory.entry.MemoryEntryMapper;
import cn.edu.scut.chatbot.memory.social.GroupStateService;
import cn.edu.scut.chatbot.memory.social.TopicLifecycleService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 群状态与主动开口端到端 IT（quickstart 场景 F）：真 MySQL + Redis，全程纯计算
 * 零模型调用（无假上游装配；llm_call_log 无新增是硬性负向断言）。
 * 静默 / 窗口衰减以「直写 Redis 时间戳 + 拨 FixedClock」构造，不真等墙钟。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class GroupStateAndProactiveIT extends AbstractIntegrationTest {

    private static final String GROUP_ID = "99702";
    private static final String BOT = "10000";
    private static final GroupKey GROUP = new GroupKey("qq", BOT, GROUP_ID);

    @Autowired
    private GroupStateService groupStateService;

    @Autowired
    private GroupStateHash groupStateHash;

    @Autowired
    private TopicLifecycleService topicLifecycleService;

    @Autowired
    private AutonomyScheduler autonomyScheduler;

    @Autowired
    private MandateGuard mandateGuard;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MemoryEntryMapper memoryEntryMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private java.time.Clock clock;

    @BeforeAll
    void seed() {
        jdbcTemplate.update("DELETE FROM transcript_entry WHERE session_id IN"
                + " (SELECT id FROM agent_session WHERE group_id = '" + GROUP_ID + "')");
        jdbcTemplate.update("DELETE FROM agent_session WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM trigger_event WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM effects_queue WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM memory_entry WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM group_profile WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM group_config WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM `group` WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM llm_call_log");
        jdbcTemplate.update("INSERT INTO `group` (platform, bot_id, group_id, group_name, lifecycle,"
                + " in_service) VALUES ('qq', '" + BOT + "', '" + GROUP_ID + "', '群状态测试群',"
                + " 'ACTIVE', 1)");
        // 群状态 Hash 清零（IT 间隔离——本 IT 自身也从重建用例覆盖丢失恢复）
        groupStateHash.deleteGroupState("qq", BOT, GROUP_ID);
        // 会话（情绪默认基线）+ 画像活跃时段：含当前小时（活跃时段内的确定性构造——
        // IT 时钟是系统钟不可拨，时段内外切换经画像 UPDATE 控制）
        sessionManager.getOrCreateSession("qq", BOT, GROUP_ID);
        jdbcTemplate.update("INSERT INTO group_profile (platform, bot_id, group_id, active_hours,"
                        + " vibe_baseline, topic_dist, active_members, updated_at) VALUES"
                        + " ('qq', '" + BOT + "', '" + GROUP_ID + "', ?, '热闹', '{}',"
                        + " '{}', CURRENT_TIMESTAMP(3))",
                "{\"" + LocalDateTime.now(clock).getHour() + "\":50}");
    }

    private void silenceFor(long millis) {
        groupStateHash.rewriteLastMsgAt("qq", BOT, GROUP_ID, clock.millis() - millis);
    }

    /**
     * 调度器真实登记的破冰触发行数（dedup_key 前缀区分——预算占位行不计入）
     */
    private long silenceBreakCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trigger_event WHERE group_id = '" + GROUP_ID + "'"
                        + " AND trigger_kind = 'internal' AND event_type = 'silenceBreak'"
                        + " AND dedup_key LIKE 'scheduled:%'",
                Long.class);
    }

    private void clearInternalTriggers() {
        jdbcTemplate.update("DELETE FROM trigger_event WHERE group_id = '" + GROUP_ID + "'"
                + " AND trigger_kind = 'internal'");
    }

    private void insertSpentBudget(int count) {
        LocalDateTime now = LocalDateTime.now(clock);
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("INSERT INTO trigger_event (id, platform, bot_id, group_id,"
                    + " trigger_kind, event_type, dedup_key, digest, decision, occurred_at)"
                    + " VALUES (" + (700000L + i) + ", 'qq', '" + BOT + "', '" + GROUP_ID + "',"
                    + " 'internal', 'silenceBreak', 'spent-" + i + "', '预算占用', 'ignore', '"
                    + now + "')");
        }
    }

    private void setEmotion(String label, int intensity) {
        LocalDateTime updatedAt = LocalDateTime.now(clock);
        jdbcTemplate.update("UPDATE agent_session SET emotion = ? WHERE group_id = ?",
                "{\"label\":\"" + label + "\",\"intensity\":" + intensity
                        + ",\"cause\":null,\"updatedAt\":\"" + updatedAt
                        + "\",\"baseLabel\":\"平静\"}",
                GROUP_ID);
    }

    // =====================================================================
    // 场景 F：六状态项与主动策略
    // =====================================================================

    @Test
    @Order(1)
    void 六状态项事件驱动更新_零模型调用_速率窗口拨钟衰减() {
        groupStateService.onGroupMessage(GROUP, "userA", "早");
        groupStateService.onGroupMessage(GROUP, "userA", "聊装修");
        groupStateService.onGroupMessage(GROUP, "userB", "预算多少");
        groupStateService.onBotSend(GROUP);
        groupStateService.onBotSend(GROUP);

        GroupStateService.GroupStateSnapshot snapshot = groupStateService.read(GROUP);
        assertThat(snapshot.msgRatePerHour()).isEqualTo(3);
        assertThat(snapshot.agentSend24h()).isEqualTo(2);
        assertThat(snapshot.activeMembersCount()).isEqualTo(2);
        assertThat(snapshot.silenceMillis()).isLessThan(Duration.ofMinutes(1).toMillis());

        // 窗口起点直写回 1 小时前——旧消息计数读时衰减归零（不拨钟，时间戳即钟面）
        groupStateHash.rewriteMsgRate("qq", BOT, GROUP_ID,
                clock.millis() - Duration.ofHours(1).toMillis() - 1000, 3);
        assertThat(groupStateService.read(GROUP).msgRatePerHour()).isZero();
    }

    @Test
    @Order(2)
    void 静默二十九分钟不触发_群消息到达计时归零() {
        silenceFor(Duration.ofMinutes(29).toMillis());
        autonomyScheduler.scanGroupSilence();
        assertThat(silenceBreakCount()).isZero();

        groupStateService.onGroupMessage(GROUP, "userA", "回来了");
        assertThat(groupStateService.read(GROUP).silenceMillis())
                .isLessThan(Duration.ofMinutes(1).toMillis());
    }

    @Test
    @Order(3)
    void 静默达三十分钟_活跃时段内产生破冰内部触发并过评估闸门() {
        silenceFor(Duration.ofMinutes(31).toMillis());
        autonomyScheduler.scanGroupSilence();

        assertThat(silenceBreakCount()).isEqualTo(1);
        // 不设旁路：登记 pending 决策留档 + assessment Effect 入队（照常过 L0/L1）
        String decision = jdbcTemplate.queryForObject(
                "SELECT decision FROM trigger_event WHERE group_id = '" + GROUP_ID + "'"
                        + " AND trigger_kind = 'internal'", String.class);
        assertThat(decision).isEqualTo(TriggerEventDO.DECISION_PENDING);
        String effectKind = jdbcTemplate.queryForObject(
                "SELECT kind FROM effects_queue WHERE group_id = '" + GROUP_ID + "'", String.class);
        assertThat(effectKind).isEqualTo("assessment");
    }

    @Test
    @Order(4)
    void 活跃时段外_静默再久也不触发() {
        long before = silenceBreakCount();
        // 画像活跃时段改为只含「当前小时之外」的小时（如凌晨 3 点）——时段外判定确定性成立
        int currentHour = LocalDateTime.now(clock).getHour();
        int offHour = (currentHour + 7) % 24;
        jdbcTemplate.update("UPDATE group_profile SET active_hours = ? WHERE group_id = '"
                + GROUP_ID + "'", "{\"" + offHour + "\":50}");

        silenceFor(Duration.ofHours(5).toMillis());
        autonomyScheduler.scanGroupSilence();
        assertThat(silenceBreakCount()).as("活跃时段外（凌晨口径）不产生破冰触发")
                .isEqualTo(before);

        // 恢复含当前小时的画像（后续用例时段内）
        jdbcTemplate.update("UPDATE group_profile SET active_hours = ? WHERE group_id = '"
                + GROUP_ID + "'", "{\"" + currentHour + "\":50}");
    }

    @Test
    @Order(5)
    void 两小时内第二次主动开口被最小间隔拦截() {
        long before = silenceBreakCount();
        autonomyScheduler.scanGroupSilence();
        autonomyScheduler.scanGroupSilence();
        assertThat(silenceBreakCount()).as("间隔窗口（2 小时）内第二次被拦").isEqualTo(before);
    }

    @Test
    @Order(6)
    void 当日预算十条用尽_不再触发() {
        clearInternalTriggers();
        silenceFor(Duration.ofMinutes(31).toMillis());
        insertSpentBudget(10);
        autonomyScheduler.scanGroupSilence();
        assertThat(silenceBreakCount()).as("预算用尽（10 条已花）不再触发").isZero();
    }

    @Test
    @Order(7)
    void 强负面情绪_预算减半为五条且破冰阈值提高() {
        clearInternalTriggers();
        setEmotion("不爽", 80);
        jdbcTemplate.update("DELETE FROM trigger_event WHERE group_id = '" + GROUP_ID + "'"
                + " AND trigger_kind = 'internal'");

        // 阈值提高 1.5 倍（30 → 45）：静默 40 分钟不足
        silenceFor(Duration.ofMinutes(40).toMillis());
        autonomyScheduler.scanGroupSilence();
        assertThat(silenceBreakCount()).as("强负面下阈值提高到 45 分钟——40 分钟不触发").isZero();

        // 静默 46 分钟过阈值；已花 5 条（预算减半后用尽）
        silenceFor(Duration.ofMinutes(46).toMillis());
        insertSpentBudget(5);
        autonomyScheduler.scanGroupSilence();
        assertThat(silenceBreakCount()).as("强负面预算减半为 5 条——5 条已花不再触发").isZero();

        // 情绪回基线后预算恢复 10：已花 5 条仍可触发
        setEmotion("平静", 10);
        autonomyScheduler.scanGroupSilence();
        assertThat(silenceBreakCount()).as("基线情绪下预算 10——已花 5 条仍可触发").isEqualTo(1);
    }

    @Test
    @Order(8)
    void Redis群状态丢失后_从转录重算重建一致() {
        // Redis 清零后再做（快照全部来自本用例动作——与重建口径一致）
        groupStateHash.deleteGroupState("qq", BOT, GROUP_ID);

        // 真实消息流进转录 + 事件驱动进 Redis
        AgentSessionDO session = sessionManager.getOrCreateSession("qq", BOT, GROUP_ID);
        UserMessage userA = new UserMessage("980001", clock.instant(), 100, null,
                "userA", List.of());
        UserMessage userB = new UserMessage("980002", clock.instant(), 100, null,
                "userB", List.of());
        sessionManager.appendTranscript(session, List.of(userA, userB), null);
        sessionManager.appendTranscript(session, List.of(new AssistantMessage(
                "980003", clock.instant(), 100, null, "回复", null, "stop", null)), null);
        groupStateService.onGroupMessage(GROUP, "userA", "在吗");
        groupStateService.onGroupMessage(GROUP, "userB", "嗯嗯");
        groupStateService.onBotSend(GROUP);

        GroupStateService.GroupStateSnapshot before = groupStateService.read(GROUP);
        assertThat(before.msgRatePerHour()).isEqualTo(2);
        assertThat(before.agentSend24h()).isEqualTo(1);
        assertThat(before.activeMembersCount()).isEqualTo(2);

        groupStateHash.deleteGroupState("qq", BOT, GROUP_ID);
        assertThat(groupStateService.read(GROUP).msgRatePerHour()).isZero();

        groupStateService.rebuild(GROUP);
        GroupStateService.GroupStateSnapshot rebuilt = groupStateService.read(GROUP);
        assertThat(rebuilt.msgRatePerHour()).isEqualTo(before.msgRatePerHour());
        assertThat(rebuilt.activeMembersCount()).isEqualTo(before.activeMembersCount());
        assertThat(rebuilt.agentSend24h()).isEqualTo(before.agentSend24h());
        // 静默基准：事件时刻与转录时刻同为「刚刚」——重建后仍在 1 分钟内（系统钟毫秒差不作精确等值）
        assertThat(Math.abs(rebuilt.silenceMillis() - before.silenceMillis()))
                .isLessThan(Duration.ofMinutes(1).toMillis());
    }

    // =====================================================================
    // 话题生命周期与 Mandate
    // =====================================================================

    private long insertTopicEntry(long id, List<String> keywords, String lifecycle) {
        MemoryEntryDO entry = new MemoryEntryDO();
        entry.setId(id);
        entry.setPlatform("qq");
        entry.setBotId(BOT);
        entry.setGroupId(GROUP_ID);
        entry.setMemoryType(MemoryEntryDO.TYPE_TOPIC);
        entry.setContent(String.join("、", keywords));
        try {
            entry.setKeywords(cn.edu.scut.chatbot.bootstrap.JsonMappers.shared()
                    .writeValueAsString(keywords));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        entry.setTopicLifecycle(lifecycle);
        entry.setOccurredAt(LocalDateTime.now(clock));
        entry.setSedimentAt(LocalDateTime.now(clock));
        entry.setStrength(50);
        entry.setStatus(MemoryEntryDO.STATUS_ACTIVE);
        memoryEntryMapper.insertEntry(entry);
        return id;
    }

    @Test
    @Order(9)
    void 话题装修_连续两次压缩不含cooling_超期closed_另一话题关键词再热回hot() {
        long decoration = insertTopicEntry(910001L, List.of("装修", "预算"),
                MemoryEntryDO.TOPIC_HOT);
        long tiles = insertTopicEntry(910002L, List.of("瓷砖"),
                MemoryEntryDO.TOPIC_COOLING);

        // 连续两次压缩摘要不含「装修」→ cooling
        topicLifecycleService.onCompactionRefreshed(GROUP, List.of("聊了别的"));
        assertThat(memoryEntryMapper.selectById(decoration).getTopicLifecycle())
                .isEqualTo(MemoryEntryDO.TOPIC_HOT);
        topicLifecycleService.onCompactionRefreshed(GROUP, List.of("聊了别的"));
        assertThat(memoryEntryMapper.selectById(decoration).getTopicLifecycle())
                .isEqualTo(MemoryEntryDO.TOPIC_COOLING);

        // cooling 超期 30 天 → 日批兜底 closed（以库 NOW 为基准回写——同源无时区错位）
        jdbcTemplate.update("UPDATE memory_entry SET updated_at ="
                + " DATE_SUB(NOW(3), INTERVAL 31 DAY) WHERE id = " + decoration);
        topicLifecycleService.runDailyCloseSweep();
        assertThat(memoryEntryMapper.selectById(decoration).getTopicLifecycle())
                .isEqualTo(MemoryEntryDO.TOPIC_CLOSED);

        // 冷却话题关键词（瓷砖）在群消息再现 → 再热回 hot（零模型）
        topicLifecycleService.onGroupMessage(GROUP, "这批瓷砖到了，效果不错");
        assertThat(memoryEntryMapper.selectById(tiles).getTopicLifecycle())
                .isEqualTo(MemoryEntryDO.TOPIC_HOT);
    }

    @Test
    @Order(10)
    void mandate清单外类别Task创建即失格_清单变更触发存量重验证() {
        jdbcTemplate.update("INSERT INTO group_config (platform, bot_id, group_id, user_blacklist,"
                + " allowed_member_task_categories, allowed_autonomous_categories, disabled_tools)"
                + " VALUES ('qq', '" + BOT + "', '" + GROUP_ID + "', '[]', '[]',"
                + " '[\"每日问候\"]', '[]')");

        TaskDO topicTask = new TaskDO();
        topicTask.setPlatform("qq");
        topicTask.setBotId(BOT);
        topicTask.setGroupId(GROUP_ID);
        topicTask.setCategory("话题再引入");
        topicTask.setMandateSource(TaskDO.MANDATE_AUTONOMOUS);
        assertThrows(cn.edu.scut.chatbot.exception.CommandRejectedException.class,
                () -> mandateGuard.assertCreatable(topicTask),
                "清单不含「话题再引入」——创建即失格");

        // 清单变更：加入类别 → 放行；再收窄 → 存量重验证失格
        jdbcTemplate.update("UPDATE group_config SET allowed_autonomous_categories ="
                + " '[\"每日问候\",\"话题再引入\"]' WHERE group_id = '" + GROUP_ID + "'");
        assertThat(mandateGuard.isDisqualified(topicTask)).isFalse();
        jdbcTemplate.update("UPDATE group_config SET allowed_autonomous_categories ="
                + " '[\"每日问候\"]' WHERE group_id = '" + GROUP_ID + "'");
        assertThat(mandateGuard.isDisqualified(topicTask)).isTrue();
    }

    @Test
    @Order(11)
    void 全程零模型调用_llmCallLog无新增() {
        Long calls = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM llm_call_log", Long.class);
        assertThat(calls).as("群状态、破冰判定、话题状态机全部纯计算零模型调用").isZero();
    }
}
