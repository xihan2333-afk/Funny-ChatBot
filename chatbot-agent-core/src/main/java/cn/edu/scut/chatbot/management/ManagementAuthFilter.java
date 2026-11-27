package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 管理面统一认证过滤器（T084，admin-auth 契约第 1 节，FR-019）：
 * <ul>
 *   <li>拦截全部 {@code /api/management/**} 路径（包含只读观测查询接口），仅放行登录接口</li>
 *   <li>支持 {@code Authorization: Bearer <token>} 与 {@code X-Admin-Token} 两种传参</li>
 *   <li>{@code must_change=true} 拦截矩阵：未改密前除改密、登出与状态查询接口外全部 403 拒绝</li>
 *   <li>第一版管理面不暴露公网：部署时应将 server.address 绑定本机/内网或由反向代理加访问控制</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ManagementAuthFilter extends OncePerRequestFilter {

    static final String MANAGEMENT_PREFIX = "/api/management";
    static final String PATH_LOGIN = "/api/management/auth/login";
    static final String PATH_CHANGE_PASSWORD = "/api/management/auth/change-password";
    static final String PATH_LOGOUT = "/api/management/auth/logout";
    static final String PATH_STATUS = "/api/management/auth/status";

    public static final String ATTR_OPERATOR = "adminOperator";
    public static final String ATTR_TOKEN = "adminToken";

    private final AdminAccountService adminAccountService;

    public ManagementAuthFilter(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(MANAGEMENT_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (PATH_LOGIN.equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        Optional<AdminAccountService.SessionPrincipal> sessionOpt = adminAccountService.verifySession(token);
        if (sessionOpt.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "security.unauthorized",
                    "管理会话无效或已过期，请重新登录");
            return;
        }

        AdminAccountService.SessionPrincipal session = sessionOpt.get();
        // must_change 拦截矩阵：首次登录未改密前，除改密与登出/状态查询外全部 403 拒绝
        if (session.mustChange() && !isAllowedDuringMustChange(path)) {
            writeError(response, HttpStatus.FORBIDDEN, "security.mustChangePassword",
                    "首次登录必须先修改默认口令后才可访问其它管理接口");
            return;
        }

        request.setAttribute(ATTR_OPERATOR, session.username());
        request.setAttribute(ATTR_TOKEN, session.token());
        filterChain.doFilter(request, response);
    }

    private static boolean isAllowedDuringMustChange(String path) {
        return PATH_CHANGE_PASSWORD.equals(path)
                || PATH_LOGOUT.equals(path)
                || PATH_STATUS.equals(path);
    }

    static String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        String headerToken = request.getHeader("X-Admin-Token");
        return headerToken != null ? headerToken.trim() : null;
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                            String errorCode, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = JsonMappers.valueToStringOrNull(
                Map.of("errorCode", errorCode, "message", message));
        response.getWriter().write(body != null ? body : "{}");
    }
}
