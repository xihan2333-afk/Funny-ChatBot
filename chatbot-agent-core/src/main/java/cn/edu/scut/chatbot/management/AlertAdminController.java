package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.observability.alert.AlertAdminService;
import cn.edu.scut.chatbot.observability.alert.AlertNotificationService;
import cn.edu.scut.chatbot.observability.alert.AlertNotificationView;
import cn.edu.scut.chatbot.observability.alert.AlertRuleView;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警管理门面（规则维护 / 通知列表与已读，observe-query 契约第 2、4 节）：
 * builtin 规则的删除拒绝语义在 AlertAdminService 层（403 之外的拒绝按 400 语义
 * 返回错误码）。认证过滤器归 US9。
 */
@RestController
@RequestMapping("/api/management/alert")
public class AlertAdminController {

    private final AlertAdminService alertAdminService;
    private final AlertNotificationService notificationService;

    public AlertAdminController(AlertAdminService alertAdminService,
                                AlertNotificationService notificationService) {
        this.alertAdminService = alertAdminService;
        this.notificationService = notificationService;
    }

    // ===== 规则维护 =====

    @GetMapping("/rules")
    public List<AlertRuleView> listRules() {
        return alertAdminService.listRules();
    }

    @PostMapping("/rules")
    public Map<String, Long> createRule(@RequestBody AlertAdminService.RuleUpsertRequest request) {
        return Map.of("id", alertAdminService.createRule(request));
    }

    @PutMapping("/rules/{id}")
    public void updateRule(@PathVariable long id,
                           @RequestBody AlertAdminService.RuleUpsertRequest request) {
        alertAdminService.updateRule(id, request);
    }

    @DeleteMapping("/rules/{id}")
    public void deleteRule(@PathVariable long id) {
        alertAdminService.deleteRule(id);
    }

    // ===== 通知中心 =====

    @GetMapping("/notifications")
    public List<AlertNotificationView> listNotifications(@RequestParam(required = false) String level,
                                                         @RequestParam(required = false) Boolean unreadOnly,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return notificationService.list(level, unreadOnly, page, size);
    }

    @PostMapping("/notifications/{id}/ack")
    public void acknowledge(@PathVariable long id) {
        notificationService.acknowledge(id);
    }
}
