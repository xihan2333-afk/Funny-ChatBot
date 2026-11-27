package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.memory.persona.PersonaMapper;
import cn.edu.scut.chatbot.memory.persona.PersonaResolver;
import cn.edu.scut.chatbot.security.credential.AdminAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 群生命周期与六块配置集成测试（US2 / T037）：
 * 验证 V9 迁移、人格切换双源同步与懒建会话兜底、Mandate 收紧批量取消在途任务、
 * 群销毁沉淀策略与自动审计落库。
 */
@AutoConfigureMockMvc
class GroupLifecycleManagementIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineCommandFacades.EngineLifecycleCommands lifecycleCommands;

    @Autowired
    private EngineCommandFacades.EngineTaskCommands taskCommands;

    @Autowired
    private PersonaMapper personaMapper;

    @Autowired
    private PersonaResolver personaResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminAccountService adminAccountService;

    @Autowired
    private AdminAccountMapper adminAccountMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void groupConfigPersonaLazyFallbackMandateTightenAndDestroyWithAudit() throws Exception {
        String platform = "onebot-v11";
        String botId = "bot-it-us2";
        String groupId = "grp-it-us2";

        lifecycleCommands.discoverGroup(platform, botId, groupId, "US2 集成测试群");
        // 授权 member:weather_query
        var personas = personaMapper.selectAllPersonas();
        assertThat(personas).isNotEmpty();
        long targetPersonaId = personas.get(0).getId();

        String token = obtainValidToken();

        // 1. 首次绑定人格（此时 agent_session 尚未创建，验证双源同步不报错且 PersonaResolver 兜底读取）
        mockMvc.perform(put("/api/management/groups/{p}/{b}/{g}/config", platform, botId, groupId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "personaId": %d,
                                  "personaSwitchReason": "初始化绑定",
                                  "allowedMemberTaskCategories": ["weather_query"],
                                  "allowedAutonomousCategories": [],
                                  "disabledTools": [],
                                  "disabledSkills": [],
                                  "userBlacklist": [],
                                  "transcriptRetainPolicy": "keep"
                                }
                                """.formatted(targetPersonaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true));

        var resolved = personaResolver.resolve(platform, botId, groupId);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo(targetPersonaId);

        Integer histCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM group_persona_history WHERE platform=? AND bot_id=? AND group_id=?",
                Integer.class, platform, botId, groupId);
        assertThat(histCount).isEqualTo(1);

        // 2. 创建一条 member:weather_query 任务，然后收紧 Mandate 预检并保存取消
        long taskId = taskCommands.createTask(new EngineViews.TaskCreation(
                platform, botId, groupId, "light", "weather_query",
                "member", "ref-1", "u-1", "{\"q\":\"bj\"}",
                java.time.LocalDateTime.now().plusHours(1).toString()));
        assertThat(taskId).isPositive();

        mockMvc.perform(post("/api/management/groups/{p}/{b}/{g}/config/precheck", platform, botId, groupId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "allowedMemberTaskCategories": [],
                                  "allowedAutonomousCategories": [],
                                  "userBlacklist": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskCount").value(1));

        mockMvc.perform(put("/api/management/groups/{p}/{b}/{g}/config", platform, botId, groupId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "personaId": %d,
                                  "allowedMemberTaskCategories": [],
                                  "allowedAutonomousCategories": [],
                                  "disabledTools": [],
                                  "disabledSkills": [],
                                  "userBlacklist": [],
                                  "transcriptRetainPolicy": "keep"
                                }
                                """.formatted(targetPersonaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledTaskCount").value(1));

        // 3. 销毁群并验证沉淀策略与审计落库
        mockMvc.perform(post("/api/management/groups/{p}/{b}/{g}/destroy", platform, botId, groupId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "retainTranscriptToMemory": false,
                                  "confirmGroupId": "%s"
                                }
                                """.formatted(groupId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destroyed").value(true));

        String policy = jdbcTemplate.queryForObject(
                "SELECT transcript_retain_policy FROM group_config WHERE platform=? AND bot_id=? AND group_id=?",
                String.class, platform, botId, groupId);
        assertThat(policy).isEqualTo("discard");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security_event WHERE event_kind = 'adminOperate'",
                Integer.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(3);
    }

    private String obtainValidToken() {
        return ManagementAuthTestHelper.obtainValidToken(adminAccountMapper, adminAccountService, redisTemplate);
    }
}
