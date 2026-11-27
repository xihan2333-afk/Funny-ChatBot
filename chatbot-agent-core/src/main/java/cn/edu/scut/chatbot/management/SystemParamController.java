package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.bootstrap.SystemParamAdminService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 系统参数五域分组管理 REST（A4）：修改后立即热刷新并广播 ConfigRefreshedEvent。
 */
@RestController
@RequestMapping("/api/management/system-params")
public class SystemParamController {

    private final SystemParamAdminService systemParamAdminService;

    public SystemParamController(SystemParamAdminService systemParamAdminService) {
        this.systemParamAdminService = systemParamAdminService;
    }

    @GetMapping
    public Map<String, List<SystemParamAdminService.ParamItemView>> listGrouped() {
        return systemParamAdminService.listGrouped();
    }

    @PutMapping("/{paramKey}")
    @AuditAction(target = "system_param", action = "update", summary = "热更新系统参数")
    public SystemParamAdminService.ParamItemView update(
            @PathVariable String paramKey,
            @RequestBody UpdateParamRequest request,
            HttpServletRequest httpRequest) {
        httpRequest.setAttribute(ManagementAuditInterceptor.ATTR_AUDIT_DETAIL, Map.of(
                "paramKey", paramKey, "paramValue", request.paramValue()));
        return systemParamAdminService.updateParam(paramKey, request.paramValue());
    }

    public record UpdateParamRequest(String paramValue) {
    }
}
