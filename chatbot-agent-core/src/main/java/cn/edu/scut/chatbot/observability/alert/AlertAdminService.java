package cn.edu.scut.chatbot.observability.alert;

import java.util.List;

/**
 * 告警规则管理门面（observe-query 契约第 2 节）：规则增改查与 builtin 保护。
 * builtin 规则允许停用、不允许删除（服务层拒绝 + 告警事件留审计）；第一版自定义
 * 规则限内置 metric 枚举（metric 必须有对应探针实现，否则引擎无法执行）。
 */
public interface AlertAdminService {

    List<AlertRuleView> listRules();

    /**
     * 新建自定义规则（builtin=0）；metric 必须是已注册探针承接的标识
     *
     * @return 新规则 id
     */
    long createRule(RuleUpsertRequest request);

    void updateRule(long id, RuleUpsertRequest request);

    /**
     * 删除自定义规则；builtin 规则抛 IllegalArgumentException 拒绝（可停用不可删）
     */
    void deleteRule(long id);

    /**
     * 规则增改统一入参（直白字段，threshold 为 JSON 字符串原样落库）
     */
    record RuleUpsertRequest(String name,
                             String metric,
                             Integer windowMin,
                             String threshold,
                             String level,
                             Boolean enabled) {
    }
}
