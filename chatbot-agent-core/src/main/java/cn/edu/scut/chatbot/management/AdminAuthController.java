package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.exception.CommandRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理平台单账号认证 REST（T083/T084）：登录、登出、修改口令、改密状态查询。
 */
@RestController
@RequestMapping("/api/management/auth")
public class AdminAuthController {

    private final AdminAccountService adminAccountService;

    public AdminAuthController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            AdminAccountService.LoginResult result = adminAccountService.login(
                    request.username(), request.password());
            return ResponseEntity.ok(result);
        } catch (CommandRejectedException rejected) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ManagementExceptionAdvice.ApiError("security.authFailed", rejected.getReason()));
        }
    }

    @PostMapping("/logout")
    public void logout(@RequestAttribute(name = ManagementAuthFilter.ATTR_TOKEN, required = false) String token) {
        adminAccountService.logout(token);
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request) {
        try {
            adminAccountService.changePassword(request.oldPassword(), request.newPassword());
            return ResponseEntity.ok(Map.of("changed", true, "reloginRequired", true));
        } catch (CommandRejectedException rejected) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ManagementExceptionAdvice.ApiError("security.invalidOldPassword", rejected.getReason()));
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        return Map.of(
                "username", operator != null ? operator : "",
                "mustChange", adminAccountService.mustChangePending()
        );
    }

    public record LoginRequest(String username, String password) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }
}
