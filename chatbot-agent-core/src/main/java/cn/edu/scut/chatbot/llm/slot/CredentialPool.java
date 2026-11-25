package cn.edu.scut.chatbot.llm.slot;

/**
 * 凭证池接口（contracts/slot-and-credentials.md 第 3 节）：多 key 轮转分摊配额 +
 * 反馈式冷却（限流）与停用（鉴权失败）。
 *
 * <p>翻译器不感知池：它拿到的只是已解析的字符串凭证（ModelRequest.resolvedCredential）。
 * 反馈回路由网关在 Failed(llm.rate_limited / llm.auth) 时调用——错误码与反馈的联动
 * 内聚网关，不散落调用方。US4 以内存假实现支撑测试，JDBC 实现（JdbcCredentialPool）归 US6。
 */
public interface CredentialPool {

    /**
     * 取下一把可用 key（按行轮转；过滤停用与冷却未到期）。全部不可用返回 null——
     * resolveCredentials 钩子按无凭证转错误结果，Run 失败进 Effects 重试。
     */
    String nextAvailable(String providerCode);

    /**
     * 反馈：限流 → 冷却当次 key（到期自动恢复）；鉴权失败 → 停用并告警（终态）。
     *
     * @param usedKeyMask 本次使用的凭证掩码（网关侧由明文生成——明文只在拼装请求头
     *                    瞬间存在，不落日志；池按掩码定位行）
     */
    void feedback(String providerCode, String usedKeyMask, FeedbackKind kind);

    /**
     * 反馈类别
     */
    enum FeedbackKind {
        RATE_LIMITED, AUTH_FAILED
    }

    /**
     * 凭证掩码生成（脱敏纪律：任何读路径与日志只暴露掩码）——
     * 形如 {@code sk-***abc}：保留前 3 与后 3 字符，中间以 *** 掩埋；短凭证整体掩埋。
     */
    static String maskOf(String credential) {
        if (credential == null || credential.length() < 8) {
            return "***";
        }
        return credential.substring(0, 3) + "***" + credential.substring(credential.length() - 3);
    }
}
