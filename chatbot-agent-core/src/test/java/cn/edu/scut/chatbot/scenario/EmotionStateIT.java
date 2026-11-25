// 守护纪律：规则与自报双通道生效、防操纵钳制（单次 ≤30、日累计封顶）、懒衰减读时
// 纯函数（拨 updatedAt 即拨钟——全程无定时任务参与）、群暂停计时顺延、崩溃恢复回
// 人格基线、事件双落点（情绪 delta + 主体好感 delta 同一次处理）、情绪不进
// systemPrompt（负向断言——前缀缓存不破坏）、全程零模型调用（llm_call_log 无新增）
// （quickstart 场景 E；contracts/persona-and-emotion.md §5/§6）
package cn.edu.scut.chatbot.scenario;

import cn.edu.scut.chatbot.bootstrap.GroupKey;
import cn.edu.scut.chatbot.bootstrap.SystemParamService;
import cn.edu.scut.chatbot.contract.envelope.EventEnvelope;
import cn.edu.scut.chatbot.contract.in.GroupMessagePayload;
import cn.edu.scut.chatbot.contract.message.AtPart;
import cn.edu.scut.chatbot.contract.message.MessagePart;
import cn.edu.scut.chatbot.contract.message.TextPart;
import cn.edu.scut.chatbot.engine.session.SessionManager;
import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.loop.RunConfig;
import cn.edu.scut.chatbot.memory.emotion.EmotionRuleEngine;
import cn.edu.scut.chatbot.memory.emotion.EmotionService;
import cn.edu.scut.chatbot.memory.emotion.EmotionState;
import cn.edu.scut.chatbot.memory.profile.UserProfileDO;
import cn.edu.scut.chatbot.memory.profile.UserProfileMapper;
import cn.edu.scut.chatbot.prompt.PromptAssembler;
import cn.edu.scut.chatbot.tool.builtin.StubTools;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时情绪端到端 IT（quickstart 场景 E）：真 MySQL，全程纯计算零模型调用
 * （无假上游装配；llm_call_log 无新增是硬性负向断言）。
 * 懒衰减以「直写 emotion.updatedAt 到 N 小时前」拨钟（读是纯函数——基准即钟面）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "chatbot.credential.key="
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class EmotionStateIT extends AbstractIntegrationTest {

    private static final String GROUP_ID = "99701";
    private static final String BOT = "10000";
    private static final GroupKey GROUP = new GroupKey("qq", BOT, GROUP_ID);

    @Autowired
    private EmotionService emotionService;

    @Autowired
    private EmotionRuleEngine emotionRuleEngine;

    @Autowired
    private StubTools.UpdateEmotionTool updateEmotionTool;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private UserProfileMapper profileMapper;

    @Autowired
    private PromptAssembler promptAssembler;

    @Autowired
    private SystemParamService systemParams;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private java.time.Clock clock;

    @BeforeAll
    void seed() {
        jdbcTemplate.update("DELETE FROM agent_session WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM user_profile WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM emotion_log WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("DELETE FROM `group` WHERE group_id = '" + GROUP_ID + "'");
        jdbcTemplate.update("INSERT INTO `group` (platform, bot_id, group_id, group_name, lifecycle,"
                + " in_service) VALUES ('qq', '" + BOT + "', '" + GROUP_ID + "', '情绪测试群',"
                + " 'ACTIVE', 1)");
        jdbcTemplate.update("DELETE FROM llm_call_log");
        // 会话绑定默认人格（V4 种子小趣：基线「平静」敏感度缺省 1.0）
        sessionManager.getOrCreateSession("qq", BOT, GROUP_ID);
        systemParams.refresh();
    }

    private void setEmotion(String label, int intensity, int hoursAgo) {
        LocalDateTime updatedAt = LocalDateTime.now(clock).minusHours(hoursAgo);
        jdbcTemplate.update("UPDATE agent_session SET emotion = ? WHERE group_id = ?",
                "{\"label\":\"" + label + "\",\"intensity\":" + intensity
                        + ",\"cause\":null,\"updatedAt\":\"" + updatedAt + "\",\"baseLabel\":\"平静\"}",
                GROUP_ID);
    }

    private String storedEmotion() {
        return jdbcTemplate.queryForObject(
                "SELECT emotion FROM agent_session WHERE group_id = '" + GROUP_ID + "'", String.class);
    }

    private EventEnvelope atBotMessage(String sender) {
        List<MessagePart> parts = List.of(
                new AtPart(AtPart.TYPE_AT, BOT),
                new TextPart(TextPart.TYPE_TEXT, "在吗"));
        GroupMessagePayload payload = new GroupMessagePayload("m-1", null,
                new cn.edu.scut.chatbot.contract.in.SenderProfile(sender, "张三", "", "member", "", ""),
                parts, null);
        return new EventEnvelope("evt-1", null, "dedup-e1", "qq", BOT, GROUP_ID, sender,
                "group_message", payload, 0L, 0L, null, null, null);
    }

    // =====================================================================
    // 场景 E：双通道与懒衰减
    // =====================================================================

    @Test
    @Order(1)
    void 规则通道_艾特消息一次事件双落点_情绪与好感同批落账() {
        emotionRuleEngine.onGroupMessage(atBotMessage("u1"));

        // 情绪：默认 50（无记录回基线 50）+ atMentioned 4 × 敏感度 1.0 = 54
        assertThat(emotionService.read(GROUP).intensity()).isEqualTo(54);
        // 双落点：好感受事件幅度渐进钳制（1~3）落 3、熟悉度 +3——同一次处理落画像
        UserProfileDO profile = profileMapper.selectByGroupAndUser("qq", BOT, GROUP_ID, "u1");
        assertThat(profile.getAffinity()).isEqualTo(3);
        assertThat(profile.getFamiliarity()).isEqualTo(3);
        // emotion_log 落账（source=rule）
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM emotion_log WHERE group_id = '" + GROUP_ID
                        + "' AND source = 'rule'", Integer.class);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    @Order(2)
    void 自报通道_词汇集内生效_词汇集外拒绝不落账() {
        boolean applied = emotionService.applySelfReport(GROUP, "开心", 10, "被夸了");
        assertThat(applied).isTrue();
        assertThat(storedEmotion().replace(" ", "")).contains("\"label\":\"开心\"");

        // 词汇集外（默认八标签无「狂喜」）拒绝
        boolean rejected = false;
        try {
            emotionService.applySelfReport(GROUP, "狂喜", 10, null);
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assertThat(rejected).isTrue();
        assertThat(storedEmotion().replace(" ", "")).contains("\"label\":\"开心\"");   // 未被污染
    }

    @Test
    @Order(3)
    void updateEmotion工具_经工具面调用_封顶反馈() {
        // 灌日累计：5 组 ±19 = 190，再 +30 → 220 > 200 触发封顶
        jdbcTemplate.update("DELETE FROM emotion_log WHERE group_id = '" + GROUP_ID + "'");
        for (int i = 0; i < 5; i++) {
            emotionService.applySelfReport(GROUP, "平静", 19, null);
            emotionService.applySelfReport(GROUP, "平静", -19, null);
        }
        boolean applied = emotionService.applySelfReport(GROUP, "开心", 30, null);
        assertThat(applied).isFalse();   // 日累计封顶拒绝（轮番刷也只能推动有限幅度）

        var result = updateEmotionTool.execute(new cn.edu.scut.chatbot.loop.ToolInvocation(
                "{\"moodLabel\":\"开心\",\"intensityDelta\":30}", "qq", BOT, GROUP_ID,
                null, 1L, null, "updateEmotion"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    @Order(4)
    void 懒衰减_基准拨两小时前_强度减半向基线回归() {
        setEmotion("兴奋", 80, 2);

        EmotionState read = emotionService.read(GROUP);

        assertThat(read.intensity()).isEqualTo(40);   // 80 × 0.5^1
        assertThat(read.label()).isEqualTo("兴奋");   // 40 ≥ 20 不回基线

        setEmotion("兴奋", 80, 6);   // 三个半衰期 → 10 < 20
        EmotionState baseline = emotionService.read(GROUP);
        assertThat(baseline.label()).isEqualTo("平静");   // 低于阈值视为基线情绪
    }

    @Test
    @Order(5)
    void 群暂停_计时顺延_暂停时长不计入衰减() {
        // updatedAt 4 小时前、暂停累计 2 小时 → 有效衰减 2 小时：80 × 0.5 = 40
        setEmotion("兴奋", 80, 4);
        jdbcTemplate.update("UPDATE `group` SET paused_accumulated_millis = 7200000"
                + " WHERE group_id = '" + GROUP_ID + "'");

        assertThat(emotionService.read(GROUP).intensity()).isEqualTo(40);

        jdbcTemplate.update("UPDATE `group` SET paused_accumulated_millis = 0"
                + " WHERE group_id = '" + GROUP_ID + "'");
    }

    @Test
    @Order(6)
    void 进程崩溃恢复_情绪缺失_回人格基线() {
        // emotion 列是 MySQL JSON 类型（非法 JSON 在库内不存在）——缺失形态用 NULL 模拟
        jdbcTemplate.update("UPDATE agent_session SET emotion = NULL"
                + " WHERE group_id = '" + GROUP_ID + "'");

        EmotionState recovered = emotionService.read(GROUP);

        assertThat(recovered.label()).isEqualTo("平静");   // 重启后心情平复
    }

    @Test
    @Order(7)
    void 单次钳制_超三十的delta被钳() {
        jdbcTemplate.update("DELETE FROM emotion_log WHERE group_id = '" + GROUP_ID + "'");
        setEmotion("平静", 50, 0);

        boolean applied = emotionService.applySelfReport(GROUP, "兴奋", 100, null);

        assertThat(applied).isTrue();
        assertThat(storedEmotion().replace(" ", "")).contains("\"intensity\":80");   // 50 + 钳 30
    }

    @Test
    @Order(8)
    void 情绪不进systemPrompt_负向断言_前缀缓存不破坏() {
        setEmotion("委屈", 80, 0);

        String assembled = promptAssembler.assemble(
                RunConfig.ExecutionForm.TURN, "qq", BOT, GROUP_ID, null);

        // 情绪每次可变——进系统区即打断前缀缓存；只在现注区尾部注入
        assertThat(assembled).doesNotContain("当前心情").doesNotContain("委屈");
    }

    @Test
    @Order(9)
    void 全程零模型调用_llm_call_log无新增() {
        Long calls = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM llm_call_log", Long.class);
        assertThat(calls).isZero();   // 情绪读写全程纯计算——负向断言（无任何定时器参与）
    }
}
