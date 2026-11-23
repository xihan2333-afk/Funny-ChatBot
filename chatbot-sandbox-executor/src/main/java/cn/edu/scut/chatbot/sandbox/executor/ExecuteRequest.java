package cn.edu.scut.chatbot.sandbox.executor;

/**
 * 执行请求（契约 sandbox-executor-api.md 第 1 节，对外契约一律 record）。
 *
 * @param language 语言，第一版仅 python（其他值由服务端拒绝并回 errorCode）
 * @param code     待执行源码
 * @param stdin    标准输入，可空
 * @param limits   资源限制，可空（缺省字段由服务端按配置兜底）
 */
public record ExecuteRequest(
        String language,
        String code,
        String stdin,
        Limits limits
) {

    /**
     * 资源限制（字段可空——空值走 sandbox.defaults 配置兜底）
     */
    public record Limits(
            Integer memMb,
            Double cpuCores,
            Integer pids,
            Integer wallTimeoutSec
    ) {
    }
}
