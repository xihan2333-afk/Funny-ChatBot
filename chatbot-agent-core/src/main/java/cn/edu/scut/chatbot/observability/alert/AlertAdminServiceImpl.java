package cn.edu.scut.chatbot.observability.alert;

import cn.edu.scut.chatbot.security.audit.SecurityDims;
import cn.edu.scut.chatbot.security.audit.SecurityEventKind;
import cn.edu.scut.chatbot.security.audit.SecurityEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 告警规则管理实现：builtin 保护与 metric 枚举校验在此层收口；全部变更写
 * security_event(adminOperate) 留审计（操作人上下文待 US9 认证过滤器接入后补，
 * 当前 detail 以 pre-auth 标注）。
 */
@Service
public class AlertAdminServiceImpl implements AlertAdminService {

    private static final Set<String> LEVELS = Set.of("info", "warn", "critical");

    private final AlertRuleMapper ruleMapper;
    private final SecurityEventRecorder eventRecorder;
    private final ObjectMapper objectMapper;
    private final Set<String> knownMetrics;

    public AlertAdminServiceImpl(AlertRuleMapper ruleMapper,
                                 SecurityEventRecorder eventRecorder,
                                 ObjectMapper objectMapper,
                                 List<MetricProbe> probes) {
        this.ruleMapper = ruleMapper;
        this.eventRecorder = eventRecorder;
        this.objectMapper = objectMapper;
        this.knownMetrics = probes.stream().map(MetricProbe::metric).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<AlertRuleView> listRules() {
        return ruleMapper.selectAll().stream().map(AlertRuleView::from).toList();
    }

    @Override
    public long createRule(RuleUpsertRequest request) {
        validateUpsert(request);
        if (ruleMapper.selectByName(request.name()) != null) {
            throw new IllegalArgumentException("规则名已存在: " + request.name());
        }
        AlertRuleDO rule = new AlertRuleDO();
        rule.setName(request.name());
        rule.setMetric(request.metric());
        rule.setWindowMin(request.windowMin());
        rule.setThreshold(request.threshold());
        rule.setLevel(request.level());
        rule.setEnabled(request.enabled() == null || request.enabled());
        rule.setBuiltin(false);
        ruleMapper.insertRule(rule);
        audit("create", rule.getId(), request);
        return rule.getId();
    }

    @Override
    public void updateRule(long id, RuleUpsertRequest request) {
        validateUpsert(request);
        AlertRuleDO existing = requireRule(id);
        if (!existing.getName().equals(request.name())
                && ruleMapper.selectByName(request.name()) != null) {
            throw new IllegalArgumentException("规则名已存在: " + request.name());
        }
        // builtin 规则的 metric 不可改（内置规则的判据语义绑定探针）
        if (Boolean.TRUE.equals(existing.getBuiltin()) && !existing.getMetric().equals(request.metric())) {
            throw new IllegalArgumentException("builtin 规则的 metric 不可修改（可停用）");
        }
        AlertRuleDO rule = new AlertRuleDO();
        rule.setId(id);
        rule.setName(request.name());
        rule.setMetric(request.metric());
        rule.setWindowMin(request.windowMin());
        rule.setThreshold(request.threshold());
        rule.setLevel(request.level());
        rule.setEnabled(request.enabled() == null || request.enabled());
        ruleMapper.updateRule(rule);
        audit("update", id, request);
    }

    @Override
    public void deleteRule(long id) {
        AlertRuleDO existing = requireRule(id);
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new IllegalArgumentException(
                    "builtin 规则不允许删除（允许停用）: " + existing.getName());
        }
        ruleMapper.deleteById(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", "pre-auth");
        detail.put("action", "delete");
        detail.put("ruleId", id);
        detail.put("ruleName", existing.getName());
        eventRecorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(), detail, null);
    }

    private void validateUpsert(RuleUpsertRequest request) {
        if (request == null || isBlank(request.name()) || isBlank(request.metric())
                || request.windowMin() == null || isBlank(request.threshold())
                || isBlank(request.level())) {
            throw new IllegalArgumentException("规则的 name / metric / windowMin / threshold / level 均必填");
        }
        if (!knownMetrics.contains(request.metric())) {
            throw new IllegalArgumentException(
                    "metric 必须是内置枚举（有探针实现）: " + request.metric());
        }
        if (!LEVELS.contains(request.level())) {
            throw new IllegalArgumentException("level 必须是 info / warn / critical: " + request.level());
        }
        if (request.windowMin() <= 0) {
            throw new IllegalArgumentException("windowMin 必须为正: " + request.windowMin());
        }
        AlertThresholdSpec.parse(objectMapper, request.threshold());
    }

    private AlertRuleDO requireRule(long id) {
        AlertRuleDO rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("规则不存在: id=" + id);
        }
        return rule;
    }

    private void audit(String action, long ruleId, RuleUpsertRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", "pre-auth");
        detail.put("action", action);
        detail.put("ruleId", ruleId);
        detail.put("ruleName", request.name());
        detail.put("metric", request.metric());
        detail.put("threshold", request.threshold());
        detail.put("level", request.level());
        eventRecorder.record(SecurityEventKind.ADMIN_OPERATE, SecurityDims.none(), detail, null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
