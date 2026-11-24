package cn.edu.scut.chatbot.tool;

/**
 * 工具域错误码：tool.* 命名空间。工具执行失败以 ToolExecutionException 携带本类错误码抛出，
 * 由循环归一化为 isError 结果；「能力未配置」这类业务性失败也走 isError 结果（工具失败是
 * 业务正常分支），不进 ERROR 日志。
 */
public final class ToolErrorCodes {

    /**
     * 工具内部错误（执行器自身故障）
     */
    public static final String EXECUTION_FAILED = "tool.execution_failed";

    /**
     * 工具参数 schema 校验不过（prepare 段拦截，不执行）
     */
    public static final String INVALID_ARGUMENTS = "tool.invalid_arguments";

    /**
     * runCode 调 sandbox-executor HTTP 超时（wallTimeoutSec + 5 秒余量）
     */
    public static final String RUN_CODE_TIMEOUT = "tool.runCode.timeout";

    /**
     * runCode 调 sandbox-executor 传输失败（连接拒绝、5xx）
     */
    public static final String RUN_CODE_TRANSPORT = "tool.runCode.transport";

    /**
     * fetchWeb / HTTP 泛化工具被 SSRF 防线拦截（私有网段、非 https、重定向二跳内网）
     */
    public static final String FETCH_BLOCKED = "tool.fetch.blocked";

    /**
     * fetchWeb 出站失败（超时、响应超限）
     */
    public static final String FETCH_FAILED = "tool.fetch.failed";

    /**
     * 能力槽位未配置（generateImage / generateVoice 未绑定 image_gen / voice_gen 槽位）
     */
    public static final String CAPABILITY_NOT_CONFIGURED = "tool.capability.not_configured";

    /**
     * 泛化工具模板渲染失败（占位符缺失等配置问题）
     */
    public static final String GENERIC_RENDER_FAILED = "tool.generic.render_failed";

    /**
     * 凭证解密失败（credential 表数据或密钥不匹配）
     */
    public static final String CREDENTIAL_DECRYPT_FAILED = "tool.credential.decrypt_failed";

    private ToolErrorCodes() {
    }
}
