// 守护纪律：个体隐私数据清除接口显式confirm=true防误删确认门控与服务委托（admin-auth 契约第 4 节，T104）
package cn.edu.scut.chatbot.management;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrivacyErasureControllerTest {

    private PrivacyErasureService erasureService;
    private PrivacyErasureController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        erasureService = mock(PrivacyErasureService.class);
        controller = new PrivacyErasureController(erasureService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ManagementExceptionAdvice())
                .build();
    }

    @Test
    void confirm为false时明确拒绝且不调用清除服务() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                controller.erase(new PrivacyErasureController.ErasureRequest(
                        "qq", "b1", "g1", "u1", false), "ops-admin"));
        assertTrue(ex.getMessage().contains("confirm=true"));

        mockMvc.perform(post("/api/management/privacy/erase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"platform":"qq","botId":"b1","groupId":"g1","userId":"u1","confirm":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("management.bad_request"));

        verify(erasureService, never()).erase(any(), any(), any(), any(), any());
    }

    @Test
    void request为空时明确拒绝且不调用清除服务() {
        assertThrows(IllegalArgumentException.class, () -> controller.erase(null, "ops-admin"));
        verify(erasureService, never()).erase(any(), any(), any(), any(), any());
    }

    @Test
    void confirm为true时正常委托清除服务并返回四路清除报告() throws Exception {
        PrivacyErasureService.ErasureReport expected =
                new PrivacyErasureService.ErasureReport("qq", "b1", "g1", "u1", 2, 2, 1, 3, 5);
        when(erasureService.erase("qq", "b1", "g1", "u1", "privacy-admin")).thenReturn(expected);

        mockMvc.perform(post("/api/management/privacy/erase")
                        .requestAttr(ManagementAuthFilter.ATTR_OPERATOR, "privacy-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"platform":"qq","botId":"b1","groupId":"g1","userId":"u1","confirm":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("qq"))
                .andExpect(jsonPath("$.groupId").value("g1"))
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.deletedMemories").value(2))
                .andExpect(jsonPath("$.deletedVectorPoints").value(2))
                .andExpect(jsonPath("$.deletedProfiles").value(1))
                .andExpect(jsonPath("$.deletedRelations").value(3))
                .andExpect(jsonPath("$.deletedTranscripts").value(5));

        verify(erasureService).erase("qq", "b1", "g1", "u1", "privacy-admin");
    }
}
