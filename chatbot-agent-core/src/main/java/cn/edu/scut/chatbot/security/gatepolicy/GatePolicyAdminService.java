package cn.edu.scut.chatbot.security.gatepolicy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全闸策略管理门面（T079，management 收口）：封装黑名单、出站词库、域名黑名单、
 * 频控与熔断阈值维护——management 控制器只调用本接口与视图 record，不触达 DO / Mapper。
 */
public interface GatePolicyAdminService {

    // ===== 黑名单 =====

    List<BlacklistView> listBlacklist();

    long addBlacklist(BlacklistCreateRequest request, String operator);

    void removeBlacklist(long id, String operator);

    // ===== 出站词库 =====

    List<ContentFilterWordView> listFilterWords();

    long addFilterWord(FilterWordCreateRequest request, String operator);

    void setFilterWordEnabled(long id, boolean enabled, String operator);

    void removeFilterWord(long id, String operator);

    // ===== 域名黑名单（system_param security.domain.blacklist） =====

    List<String> getDomainBlacklist();

    void updateDomainBlacklist(List<String> domains, String operator);

    // ===== 频控 / 指纹 / 熔断阈值（system_param） =====

    Map<String, String> getThresholds();

    void updateThresholds(Map<String, String> updates, String operator);

    // ===== 注入特征规则（system_param security.delimit.featurePatterns） =====

    default List<InjectionRuleView> listInjectionRules() {
        return List.of();
    }

    default InjectionRuleView addInjectionRule(String pattern, String operator) {
        throw new UnsupportedOperationException();
    }

    default InjectionRuleView updateInjectionRule(int index, String pattern, String operator) {
        throw new UnsupportedOperationException();
    }

    default void deleteInjectionRule(int index, String operator) {
        throw new UnsupportedOperationException();
    }

    // ===== 快筛/拒发记录查询与一键转候选词 =====

    default List<FilterCandidateEventView> listFilterCandidates(int limit) {
        return List.of();
    }

    default long promoteFilterWordFromEvent(long eventId, String overrideWord, String level, String operator) {
        throw new UnsupportedOperationException();
    }

    default List<FilterCandidateEventView> listBreakerHistory(int limit) {
        return List.of();
    }

    // ===== 视图与请求契约 =====

    record InjectionRuleView(int id, String pattern) {
    }

    record FilterCandidateEventView(long eventId, String platform, String botId, String groupId,
                                    String detailJson, String traceId, LocalDateTime createdAt) {
    }

    record BlacklistView(Long id, String scope, String platform, String botId,
                         String groupId, String userId, String reason, LocalDateTime createdAt) {
    }

    record BlacklistCreateRequest(String scope, String platform, String botId,
                                  String groupId, String userId, String reason) {
    }

    record ContentFilterWordView(Long id, String word, String level, String source, boolean enabled) {
    }

    record FilterWordCreateRequest(String word, String level, String source) {
    }
}
