package cn.edu.scut.chatbot.observability.alert;

/**
 * 告警规则对外视图（management 收口：管理面只见视图不见 DO——宪法第五条）。
 */
public record AlertRuleView(long id,
                            String name,
                            String metric,
                            int windowMin,
                            String threshold,
                            String level,
                            boolean enabled,
                            boolean builtin) {

    static AlertRuleView from(AlertRuleDO rule) {
        return new AlertRuleView(rule.getId(), rule.getName(), rule.getMetric(),
                rule.getWindowMin(), rule.getThreshold(), rule.getLevel(),
                Boolean.TRUE.equals(rule.getEnabled()), Boolean.TRUE.equals(rule.getBuiltin()));
    }
}
