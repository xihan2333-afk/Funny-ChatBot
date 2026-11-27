package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.observability.eval.AttackBatchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 注入对抗测试与用例库管理 REST（T100，eval-and-flywheel 契约第 7 节）：
 * 用例库维护、上线前全量或选定执行触发、批次报告与 verdict 查询门面。
 */
@RestController
@RequestMapping("/api/management/security/attack-cases")
public class AttackCaseController {

    private final AttackBatchService attackBatchService;

    public AttackCaseController(AttackBatchService attackBatchService) {
        this.attackBatchService = attackBatchService;
    }

    @GetMapping
    public List<AttackBatchService.AttackCaseView> list(@RequestParam(required = false) String channel) {
        return attackBatchService.listCases(channel);
    }

    @PostMapping
    @SkipAutoAudit
    public Map<String, Long> create(@RequestBody AttackBatchService.AttackCaseCreateRequest request,
                                    @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        return Map.of("id", attackBatchService.createCase(request, operator));
    }

    @PutMapping("/{id}")
    @SkipAutoAudit
    public void update(@PathVariable long id,
                       @RequestBody AttackBatchService.AttackCaseCreateRequest request,
                       @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        attackBatchService.updateCase(id, request, operator);
    }

    @PutMapping("/{id}/enabled")
    @SkipAutoAudit
    public void setEnabled(@PathVariable long id,
                           @RequestParam boolean enabled,
                           @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        attackBatchService.setEnabled(id, enabled, operator);
    }

    @DeleteMapping("/{id}")
    @SkipAutoAudit
    public void delete(@PathVariable long id,
                       @RequestAttribute(name = ManagementAuthFilter.ATTR_OPERATOR, required = false) String operator) {
        attackBatchService.deleteCase(id, operator);
    }

    @PostMapping("/run")
    @AuditAction(target = "attack_case", action = "run", summary = "执行注入对抗测试")
    public AttackBatchService.AttackBatchReport run(@RequestBody(required = false) RunAttackRequest request) {
        if (request == null || request.caseIds() == null || request.caseIds().isEmpty()) {
            return attackBatchService.runAllEnabled();
        }
        return attackBatchService.runBatch(request.caseIds());
    }

    public record RunAttackRequest(List<Long> caseIds) {
    }
}
