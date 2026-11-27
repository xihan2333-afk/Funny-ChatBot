package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

/**
 * 管理平台写接口统一审计拦截器：
 * 拦截 {@code /api/management/**} 下的 POST / PUT / PATCH / DELETE 请求，
 * 自动写入 {@code security_event.adminOperate} 并对敏感键做强制脱敏。
 * <p>标注 {@link SkipAutoAudit} 的端点（Service 层已自行记录 {@code ADMIN_OPERATE}）跳过以防双写；
 * 审计落库异常仅记带 {@code errorCode=mgmt.audit_write_failed} 的 ERROR 日志，不阻断正常业务响应。
 */
@Component
public class ManagementAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ManagementAuditInterceptor.class);

    public static final String ATTR_AUDIT_DETAIL = "auditDetail";

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SENSITIVE_KEY_TOKENS = Set.of(
            "password", "secret", "apikey", "token", "newmasterkeybase64", "keyplain", "credential"
    );

    private final SecurityEventRecorder securityEventRecorder;

    public ManagementAuditInterceptor(SecurityEventRecorder securityEventRecorder) {
        this.securityEventRecorder = securityEventRecorder;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        String method = request.getMethod();
        if (method == null || !WRITE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(ManagementAuthFilter.MANAGEMENT_PREFIX)) {
            return;
        }
        if (handler instanceof HandlerMethod hm) {
            if (hm.hasMethodAnnotation(SkipAutoAudit.class)
                    || hm.getBeanType().isAnnotationPresent(SkipAutoAudit.class)) {
                return;
            }
        }

        String target = path;
        String action = method.toLowerCase(Locale.ROOT);
        String summary = method + " " + path;
        if (handler instanceof HandlerMethod hm) {
            AuditAction meta = hm.getMethodAnnotation(AuditAction.class);
            if (meta != null) {
                target = meta.target();
                action = meta.action();
                summary = (meta.summary() == null || meta.summary().isBlank())
                        ? (action + " " + target)
                        : meta.summary();
            }
        }

        Object operatorAttr = request.getAttribute(ManagementAuthFilter.ATTR_OPERATOR);
        String operator = operatorAttr != null ? operatorAttr.toString() : "anonymous";
        int httpStatus = response != null ? response.getStatus() : 200;
        boolean success = ex == null && httpStatus < 400;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", operator);
        detail.put("method", method);
        detail.put("path", path);
        detail.put("target", target);
        detail.put("action", action);
        detail.put("summary", summary);
        detail.put("httpStatus", httpStatus);
        detail.put("success", success);

        Object extraAttr = request.getAttribute(ATTR_AUDIT_DETAIL);
        if (extraAttr instanceof Map<?, ?> extraMap && !extraMap.isEmpty()) {
            detail.put("extra", sanitizeMap(extraMap));
        }

        String traceId = extractTraceId(request);
        try {
            securityEventRecorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(), detail, traceId);
        } catch (Exception recordEx) {
            log.error("管理接口审计写入失败 errorCode=mgmt.audit_write_failed path={} method={}",
                    path, method, recordEx);
        }
    }

    static Map<String, Object> sanitizeMap(Map<?, ?> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = entry.getKey() != null ? entry.getKey().toString() : "null";
            Object val = entry.getValue();
            if (isSensitiveKey(key)) {
                sanitized.put(key, "***");
            } else {
                sanitized.put(key, sanitizeValue(val));
            }
        }
        return sanitized;
    }

    private static Object sanitizeValue(Object val) {
        if (val instanceof Map<?, ?> nested) {
            return sanitizeMap(nested);
        }
        if (val instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(sanitizeValue(item));
            }
            return out;
        }
        return val;
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String token : SENSITIVE_KEY_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String extractTraceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null && !traceparent.isBlank()) {
            String[] parts = traceparent.trim().split("-");
            if (parts.length >= 2 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        String headerTraceId = request.getHeader("X-Trace-Id");
        return (headerTraceId != null && !headerTraceId.isBlank()) ? headerTraceId.trim() : null;
    }
}
