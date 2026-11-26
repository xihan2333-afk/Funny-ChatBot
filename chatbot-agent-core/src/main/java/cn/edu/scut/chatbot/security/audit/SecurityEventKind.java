package cn.edu.scut.chatbot.security.audit;

/**
 * 安全事件八类（data-model.md 2.1 节、security-gate 契约第 8 节）：全部经
 * {@link SecurityEventRecorder} 统一入口落 security_event 表，长期保留不滚动清理。
 *
 * <p>八类覆盖面：硬防线拦截四类（gateBlocked / contentFiltered / mandateDenied /
 * circuitBreak）+ 软防线留痕两类（injectDetected / sandboxAbnormal）+ 管理面与凭证两类
 * （adminOperate / credentialInvalid）。
 */
public enum SecurityEventKind {

    /**
     * beforeToolCall 总闸拒绝（策略类拦截：黑名单工具、越权参数等）
     */
    GATE_BLOCKED("gateBlocked"),

    /**
     * 出站内容被过滤：快筛词库 / 提示词指纹 / 平台拒发回流（source=platform）
     */
    CONTENT_FILTERED("contentFiltered"),

    /**
     * createTask 越权被拒（Mandate 授权不通过）
     */
    MANDATE_DENIED("mandateDenied"),

    /**
     * 注入特征检测命中（留痕不拦截——软防线只防影响）
     */
    INJECT_DETECTED("injectDetected"),

    /**
     * 沙箱输出特征或异常模式命中（留痕不拦截——环境隔离是总纲）
     */
    SANDBOX_ABNORMAL("sandboxAbnormal"),

    /**
     * 成本熔断触发 / 解除（硬闸状态变更）
     */
    CIRCUIT_BREAK("circuitBreak"),

    /**
     * 管理操作审计：登录改密、策略维护、熔断解除、越过发布门禁、个体清除等
     */
    ADMIN_OPERATE("adminOperate"),

    /**
     * 凭证失效（auth 类错误——2.3.6 既定信号纳入统一视图）
     */
    CREDENTIAL_INVALID("credentialInvalid");

    /**
     * 落库形态（security_event.event_kind 列的小写字符串）
     */
    private final String code;

    SecurityEventKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
