package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.fixtures.AbstractIntegrationTest;
import cn.edu.scut.chatbot.security.credential.AdminAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 能力配置发布与重嵌入集成测试（US3 / T055）：
 * 覆盖 Prompt/Skill 草稿 → 门禁触发 → 发布 → 版本历史 → 一键回滚全链路。
 */
@AutoConfigureMockMvc
class CapabilityPublishManagementIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountService adminAccountService;

    @Autowired
    private AdminAccountMapper adminAccountMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void skillDraftGatePublishAndRollbackEndToEnd() throws Exception {
        String token = obtainValidToken();

        // 1. 创建 Skill 草稿
        mockMvc.perform(post("/api/management/skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "it-skill-v1",
                                  "description": "集成测试技能 v1",
                                  "content": "# IT Skill\\nVersion 1",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"));

        // 2. 触发发布门禁并等待完成
        mockMvc.perform(post("/api/management/publish-gate/trigger?intention=skill:it-skill-v1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        for (int i = 0; i < 30; i++) {
            String resp = mockMvc.perform(get("/api/management/publish-gate/status?intention=skill:it-skill-v1")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString();
            if (!resp.contains("\"running\"")) {
                break;
            }
            Thread.sleep(50);
        }

        // 3. 发布 Skill
        mockMvc.perform(post("/api/management/skills/it-skill-v1/publish")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideReason\":\"IT test\"}"))
                .andExpect(status().isOk());

        // 4. 查询版本列表并回滚
        mockMvc.perform(get("/api/management/skills/it-skill-v1/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNo").value(2));

        mockMvc.perform(post("/api/management/skills/it-skill-v1/rollback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":2}"))
                .andExpect(status().isOk());
    }

    private String obtainValidToken() {
        return ManagementAuthTestHelper.obtainValidToken(adminAccountMapper, adminAccountService, redisTemplate);
    }
}
