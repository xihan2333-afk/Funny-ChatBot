// 守护纪律：must_change 强制改密拦截矩阵与未认证 401 拦截（admin-auth 契约第 1 节，SC-008）
package cn.edu.scut.chatbot.management;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementAuthFilterTest {

    private AdminAccountService authService;
    private ManagementAuthFilter filter;

    @BeforeEach
    void setUp() {
        authService = mock(AdminAccountService.class);
        filter = new ManagementAuthFilter(authService);
    }

    @Test
    void 登录接口免认证放行_其它管理接口无token返回401() throws Exception {
        MockHttpServletRequest loginReq = new MockHttpServletRequest("POST", "/api/management/auth/login");
        MockHttpServletResponse loginResp = new MockHttpServletResponse();
        MockFilterChain loginChain = new MockFilterChain();
        filter.doFilter(loginReq, loginResp, loginChain);
        assertEquals(200, loginResp.getStatus());
        assertNotNull(loginChain.getRequest());

        when(authService.verifySession(isNull())).thenReturn(Optional.empty());
        MockHttpServletRequest queryReq = new MockHttpServletRequest("GET", "/api/management/observe/metrics");
        MockHttpServletResponse queryResp = new MockHttpServletResponse();
        MockFilterChain queryChain = new MockFilterChain();
        filter.doFilter(queryReq, queryResp, queryChain);
        assertEquals(401, queryResp.getStatus());
        assertNull(queryChain.getRequest());
    }

    @Test
    void mustChange为true时除改密与登出外全部403拒绝含只读接口() throws Exception {
        when(authService.verifySession("tok-init"))
                .thenReturn(Optional.of(new AdminAccountService.SessionPrincipal("tok-init", "admin", 1000L, true)));

        // 只读观测接口 → 403
        MockHttpServletRequest obsReq = new MockHttpServletRequest("GET", "/api/management/observe/traces/123");
        obsReq.addHeader("Authorization", "Bearer tok-init");
        MockHttpServletResponse obsResp = new MockHttpServletResponse();
        filter.doFilter(obsReq, obsResp, new MockFilterChain());
        assertEquals(403, obsResp.getStatus());
        assertTrue(obsResp.getContentAsString().contains("security.mustChangePassword"));

        // 策略写接口 → 403
        MockHttpServletRequest policyReq = new MockHttpServletRequest("GET", "/api/management/security/blacklist");
        policyReq.addHeader("Authorization", "Bearer tok-init");
        MockHttpServletResponse policyResp = new MockHttpServletResponse();
        filter.doFilter(policyReq, policyResp, new MockFilterChain());
        assertEquals(403, policyResp.getStatus());

        // 改密接口 → 放行
        MockHttpServletRequest changeReq = new MockHttpServletRequest("POST", "/api/management/auth/change-password");
        changeReq.addHeader("Authorization", "Bearer tok-init");
        MockHttpServletResponse changeResp = new MockHttpServletResponse();
        MockFilterChain changeChain = new MockFilterChain();
        filter.doFilter(changeReq, changeResp, changeChain);
        assertEquals(200, changeResp.getStatus());
        assertNotNull(changeChain.getRequest());
    }

    @Test
    void 改密完成后全部管理接口放行并注入操作人属性() throws Exception {
        when(authService.verifySession("tok-normal"))
                .thenReturn(Optional.of(new AdminAccountService.SessionPrincipal("tok-normal", "admin", 1000L, false)));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/management/observe/metrics");
        req.addHeader("Authorization", "Bearer tok-normal");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertEquals("admin", req.getAttribute(ManagementAuthFilter.ATTR_OPERATOR));
    }

    @Test
    void 支持XAdminToken请求头_mustChange允许登出与状态查询_非管理路径直接放行() throws Exception {
        when(authService.verifySession("tok-header"))
                .thenReturn(Optional.of(new AdminAccountService.SessionPrincipal("tok-header", "admin", 1000L, true)));

        // mustChange=true 时允许 /auth/status 与 /auth/logout 携带 X-Admin-Token 放行
        for (String allowedPath : new String[]{"/api/management/auth/status", "/api/management/auth/logout"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", allowedPath);
            req.addHeader("X-Admin-Token", " tok-header ");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, resp, chain);
            assertEquals(200, resp.getStatus());
            assertNotNull(chain.getRequest(), allowedPath + " 应在 mustChange 期间放行");
        }

        // 非 /api/management 路径不进过滤器校验
        MockHttpServletRequest publicReq = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse publicResp = new MockHttpServletResponse();
        MockFilterChain publicChain = new MockFilterChain();
        filter.doFilter(publicReq, publicResp, publicChain);
        assertEquals(200, publicResp.getStatus());
        assertNotNull(publicChain.getRequest());
    }
}
