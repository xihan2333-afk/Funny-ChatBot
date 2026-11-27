package cn.edu.scut.chatbot.management;

import cn.edu.scut.chatbot.security.gatepolicy.CostBreakerService;
import cn.edu.scut.chatbot.security.gatepolicy.GatePolicyAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 安全闸策略与熔断管理 REST（T079，security-gate 契约第 4、5 节）：
 * 黑名单 / 词库 / 域名黑名单 / 频控与阈值维护、熔断状态查询与手动解除门面。
 * 控制器只调领域门面，不触达 DO / Mapper（宪法第五条 management 收口）。
 */
@RestController
@RequestMapping("/api/management/security")
public class GatePolicyController {

    private final GatePolicyAdminService adminService;
    private final CostBreakerService costBreakerService;

    public GatePolicyController(GatePolicyAdminService adminService,
                                CostBreakerService costBreakerService) {
        this.adminService = adminService;
        this.costBreakerService = costBreakerService;
    }

    // ===== 黑名单 =====

    @GetMapping("/blacklist")
    public List<GatePolicyAdminService.BlacklistView> listBlacklist() {
        return adminService.listBlacklist();
    }

    @PostMapping("/blacklist")
    @SkipAutoAudit
    public Map<String, Long> addBlacklist(@RequestBody GatePolicyAdminService.BlacklistCreateRequest request,
                                          @RequestAttribute(name = "adminOperator", required = false) String operator) {
        return Map.of("id", adminService.addBlacklist(request, operator));
    }

    @DeleteMapping("/blacklist/{id}")
    @SkipAutoAudit
    public void removeBlacklist(@PathVariable long id,
                                @RequestAttribute(name = "adminOperator", required = false) String operator) {
        adminService.removeBlacklist(id, operator);
    }

    // ===== 出站词库 =====

    @GetMapping("/filter-words")
    public List<GatePolicyAdminService.ContentFilterWordView> listFilterWords() {
        return adminService.listFilterWords();
    }

    @PostMapping("/filter-words")
    @SkipAutoAudit
    public Map<String, Long> addFilterWord(@RequestBody GatePolicyAdminService.FilterWordCreateRequest request,
                                           @RequestAttribute(name = "adminOperator", required = false) String operator) {
        return Map.of("id", adminService.addFilterWord(request, operator));
    }

    @PutMapping("/filter-words/{id}/enabled")
    @SkipAutoAudit
    public void setFilterWordEnabled(@PathVariable long id,
                                     @RequestParam boolean enabled,
                                     @RequestAttribute(name = "adminOperator", required = false) String operator) {
        adminService.setFilterWordEnabled(id, enabled, operator);
    }

    @DeleteMapping("/filter-words/{id}")
    @SkipAutoAudit
    public void removeFilterWord(@PathVariable long id,
                                 @RequestAttribute(name = "adminOperator", required = false) String operator) {
        adminService.removeFilterWord(id, operator);
    }

    // ===== 域名黑名单 =====

    @GetMapping("/domain-blacklist")
    public List<String> getDomainBlacklist() {
        return adminService.getDomainBlacklist();
    }

    @PutMapping("/domain-blacklist")
    @SkipAutoAudit
    public void updateDomainBlacklist(@RequestBody List<String> domains,
                                      @RequestAttribute(name = "adminOperator", required = false) String operator) {
        adminService.updateDomainBlacklist(domains, operator);
    }

    // ===== 阈值 =====

    @GetMapping("/thresholds")
    public Map<String, String> getThresholds() {
        return adminService.getThresholds();
    }

    @PutMapping("/thresholds")
    @SkipAutoAudit
    public void updateThresholds(@RequestBody Map<String, String> updates,
                                 @RequestAttribute(name = "adminOperator", required = false) String operator) {
        adminService.updateThresholds(updates, operator);
    }

    // ===== 成本熔断 =====

    @GetMapping("/breaker")
    public CostBreakerService.BreakerSnapshot breakerStatus() {
        return costBreakerService.aggregateDaily();
    }

    @GetMapping("/breaker/history")
    public List<GatePolicyAdminService.FilterCandidateEventView> breakerHistory(
            @RequestParam(defaultValue = "50") int limit) {
        return adminService.listBreakerHistory(limit);
    }

    @PostMapping("/breaker/release")
    @SkipAutoAudit
    public void releaseBreaker(@RequestParam(required = false, defaultValue = CostBreakerService.SCOPE_GLOBAL) String scope,
                               @RequestParam(required = false) Boolean confirm,
                               @RequestAttribute(name = "adminOperator", required = false) String operator) {
        if (Boolean.FALSE.equals(confirm)) {
            throw new IllegalArgumentException("解除成本熔断必须二次确认（confirm=true）");
        }
        costBreakerService.manualRelease(scope, operator);
    }

    // ===== 注入特征规则 =====

    @GetMapping("/injection-rules")
    public List<GatePolicyAdminService.InjectionRuleView> listInjectionRules() {
        return adminService.listInjectionRules();
    }

    @PostMapping("/injection-rules")
    @SkipAutoAudit
    public GatePolicyAdminService.InjectionRuleView addInjectionRule(
            @RequestBody InjectionRuleRequest request,
            @RequestAttribute(name = "adminOperator", required = false) String operator) {
        return adminService.addInjectionRule(request.pattern(), operator);
    }

    @PutMapping("/injection-rules/{id}")
    @SkipAutoAudit
    public GatePolicyAdminService.InjectionRuleView updateInjectionRule(
            @PathVariable int id,
            @RequestBody InjectionRuleRequest request,
            @RequestAttribute(name = "adminOperator", required = false) String operator) {
        return adminService.updateInjectionRule(id, request.pattern(), operator);
    }

    @DeleteMapping("/injection-rules/{id}")
    @SkipAutoAudit
    public void deleteInjectionRule(
            @PathVariable int id,
            @RequestAttribute(name = "adminOperator", required = false) String operator) {
        adminService.deleteInjectionRule(id, operator);
    }

    // ===== 快筛/拒发记录与一键转候选词 =====

    @GetMapping("/filter-candidates")
    public List<GatePolicyAdminService.FilterCandidateEventView> listFilterCandidates(
            @RequestParam(defaultValue = "50") int limit) {
        return adminService.listFilterCandidates(limit);
    }

    @PostMapping("/filter-words/from-event/{eventId}")
    @SkipAutoAudit
    public Map<String, Long> promoteFromEvent(
            @PathVariable long eventId,
            @RequestBody(required = false) PromoteWordRequest request,
            @RequestAttribute(name = "adminOperator", required = false) String operator) {
        long id = adminService.promoteFilterWordFromEvent(
                eventId,
                request != null ? request.word() : null,
                request != null ? request.level() : "block",
                operator);
        return Map.of("id", id);
    }

    public record InjectionRuleRequest(String pattern) {
    }

    public record PromoteWordRequest(String word, String level) {
    }
}
