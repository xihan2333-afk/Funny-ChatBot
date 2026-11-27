package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.exception.ChatBotException;
import cn.edu.scut.chatbot.exception.PayloadException;
import cn.edu.scut.chatbot.exception.RetryableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 管理接口异常语义：
 * <ul>
 *   <li>可重试错误（ES 不可达等）→ 503</li>
 *   <li>目标不存在（{@code mgmt.not_found} / {@link NoSuchElementException}）→ 404</li>
 *   <li>并发冲突（{@code mgmt.reembed_in_progress}）→ 409</li>
 *   <li>参数问题 / 命令拒绝 / 静态校验 / 维度不匹配（含附加负载）→ 400</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "cn.edu.scut.chatbot.management")
public class ManagementExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(ManagementExceptionAdvice.class);

    public static final String ERR_NOT_FOUND = "mgmt.not_found";
    public static final String ERR_REEMBED_IN_PROGRESS = "mgmt.reembed_in_progress";
    public static final String ERR_EMBED_DIM_MISMATCH = "llm.embedding_dimension_mismatch";

    /**
     * 错误响应体：点分错误码 + 人话描述 + 可选结构化负载
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ApiError(String errorCode, String message, Map<String, Object> payload) {

        public ApiError(String errorCode, String message) {
            this(errorCode, message, Map.of());
        }
    }

    @ExceptionHandler(RetryableException.class)
    public ResponseEntity<ApiError> retryable(RetryableException e) {
        log.warn("管理接口可重试错误 errorCode={}", e.getErrorCode());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(ERR_NOT_FOUND, e.getMessage() != null ? e.getMessage() : "目标资源不存在"));
    }

    @ExceptionHandler(PayloadException.class)
    public ResponseEntity<ApiError> payloadException(PayloadException e) {
        String code = e.getErrorCode() != null ? e.getErrorCode() : "mgmt.bad_request";
        HttpStatus status = resolveStatusByErrorCode(code);
        return ResponseEntity.status(status)
                .body(new ApiError(code, e.getMessage(), e.getPayload()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validationError(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("请求参数校验失败");
        return ResponseEntity.badRequest().body(new ApiError("mgmt.invalid_param", msg));
    }

    @ExceptionHandler({IllegalArgumentException.class, ChatBotException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        String errorCode = e instanceof ChatBotException c && c.getErrorCode() != null
                ? c.getErrorCode() : "management.bad_request";
        HttpStatus status = resolveStatusByErrorCode(errorCode);
        return ResponseEntity.status(status).body(new ApiError(errorCode, e.getMessage()));
    }

    private static HttpStatus resolveStatusByErrorCode(String errorCode) {
        if (ERR_NOT_FOUND.equals(errorCode)) {
            return HttpStatus.NOT_FOUND;
        }
        if (ERR_REEMBED_IN_PROGRESS.equals(errorCode)) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
