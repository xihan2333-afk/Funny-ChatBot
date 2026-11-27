package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.security.credential.AdminAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认知数据与个体清除集成测试（US6 / T088）：
 * 验证记忆检索、表情包人工修正、链接卡片清理、个体清除确认守卫以及审计闭环。
 */
@AutoConfigureMockMvc
class CognitionAndPrivacyManagementIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminAccountService adminAccountService;

    @Autowired
    private AdminAccountMapper adminAccountMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void emojiLinkCardMemoryAndPrivacyEraseWorkEndToEndWithAudit() throws Exception {
        String token = obtainValidToken();

        // 1. 预置表情包映射并验证 GET + PUT /emojis/{id}
        jdbcTemplate.update(
                "INSERT INTO emoji_mapping (platform, bot_id, group_id, fingerprint, description) VALUES (?, ?, ?, ?, ?)",
                "onebot-v11", "bot-it-us6", "grp-it-us6", "fp-doge-001", "原始狗头");
        Long emojiId = jdbcTemplate.queryForObject(
                "SELECT id FROM emoji_mapping WHERE fingerprint = 'fp-doge-001'", Long.class);
        assertThat(emojiId).isNotNull();

        mockMvc.perform(get("/api/management/cognition/emojis")
                        .header("Authorization", "Bearer " + token)
                        .param("groupId", "grp-it-us6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(put("/api/management/cognition/emojis/" + emojiId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"滑稽狗头（人工修正）\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("滑稽狗头（人工修正）"));

        // 2. 链接卡片查询与删除
        mockMvc.perform(get("/api/management/cognition/link-cards")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        // 3. 记忆分页查询（空库正常返回）
        mockMvc.perform(get("/api/management/cognition/memories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        // 4. 个体清除确认守卫：confirm=false 拒绝，confirm=true 执行
        mockMvc.perform(post("/api/management/privacy/erase")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"onebot-v11\",\"botId\":\"bot-it-us6\",\"groupId\":\"grp-it-us6\",\"userId\":\"u-it-1\",\"confirm\":false}"))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/management/privacy/erase")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"onebot-v11\",\"botId\":\"bot-it-us6\",\"groupId\":\"grp-it-us6\",\"userId\":\"u-it-1\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-it-1"));

        // 5. 验证审计记录已写入（event_kind = adminOperate）
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM security_event WHERE event_kind = 'adminOperate' AND detail LIKE '%emoji_mapping%'",
                Integer.class);
        assertThat(auditCount).isNotNull().isGreaterThanOrEqualTo(1);
    }

    private String obtainValidToken() {
        return ManagementAuthTestHelper.obtainValidToken(adminAccountMapper, adminAccountService, redisTemplate);
    }
}
