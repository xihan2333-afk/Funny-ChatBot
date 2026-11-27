package cn.edu.scut.chatbot.security.gatepolicy;

/**
 * 安全闸策略数据面（security-gate 契约第 4 节）：机制在入口、策略可运营——
 * 全部策略 MySQL / system_param 存储，管理平台维护，ConfigRefreshEvent 生效（新 Run）。
 *
 * <p>黑名单消费点是 L0 规则闸门（直接忽略绝不回应）；频控消费点是 createTask 与
 * beforeToolCall 本地执行类；域名黑名单消费点是链接预取与二级摘要前（请求发出前判定）。
 */
public interface GatePolicyService {

    boolean isBlacklisted(String platform, String botId, String groupId, String userId);

    /**
     * 群级开关（group 表 in_service + ACTIVE——运营决策非系统判断）
     */
    boolean isGroupEnabled(String platform, String botId, String groupId);

    /**
     * 链接抓取域名黑名单（system_param security.domain.blacklist JSON 数组）
     */
    boolean isDomainBlocked(String domain);

    /**
     * 重任务频控：单群单用户每日（默认 10）——check 即计数占一次
     */
    FrequencyVerdict checkHeavyTaskQuota(String platform, String botId, String groupId, String userId);

    /**
     * 重任务群级每日上限（成本滥用防护第 2 层的群级闸，与单用户口径并行）
     */
    FrequencyVerdict checkHeavyTaskGroupQuota(String platform, String botId, String groupId);

    /**
     * runCode 群级每日（默认 20）
     */
    FrequencyVerdict checkRunCodeQuota(String platform, String botId, String groupId);

    /**
     * 记录一次 Mandate 越权被拒并累加该用户小时桶计数（freq:mandate:{user}:{hourBucket}），
     * 返回本小时累计次数及阈值比对结果（达到阈值由告警探针与日志暴露）
     */
    default FrequencyVerdict recordMandateDenied(String userId) {
        return new FrequencyVerdict(true, 0, 10, "mandate:hour");
    }

    /**
     * 策略刷新事件消费（与工具热管理同机制，新 Run 生效）
     */
    void refresh();

    /**
     * 频控判定结果：allowed=窗口未超限；used/limit 供错误信息与观测口径
     */
    record FrequencyVerdict(boolean allowed, long used, long limit, String scope) {
    }
}
