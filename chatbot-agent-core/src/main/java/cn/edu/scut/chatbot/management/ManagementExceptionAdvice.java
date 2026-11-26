package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理接口异常语义：可重试错误（ES 不可达等）503 + 点分错误码——调用方拿到
 * 「明确可重试」而非挂死或模糊 500；参数问题 400。认证过滤器（US9）统一挂
 * 401/403 语义，与本 advice 并行。
 */
@RestControllerAdvice(basePackages = "cn.edu.scut.chatbot.management")
public class ManagementExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(ManagementExceptionAdvice.class);

    /**
     * 错误响应体：点分错误码 + 人话描述（宪法错误码规约）
     */
    public record ApiError(String errorCode, String message) {
    }

    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<ApiError> retryable(RetryableException e) {
        log.warn("管理接口可重试错误 errorCode={}", e.getErrorCode());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, ChatBotException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        String errorCode = e instanceof ChatBotException c && c.getErrorCode() != null
                ? c.getErrorCode() : "management.bad_request";
        return ResponseEntity.badRequest().body(new ApiError(errorCode, e.getMessage()));
    }
}
