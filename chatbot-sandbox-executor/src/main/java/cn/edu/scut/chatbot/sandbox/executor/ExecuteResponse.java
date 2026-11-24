package cn.edu.scut.chatbot.sandbox.executor;

/**
 * 执行响应（契约 sandbox-executor-api.md 第 1 节）。
 *
 * <p>业务失败也是 HTTP 200 + 本形态（传输归传输、业务归业务——执行器内部错误不抛 5xx，
 * 以 errorCode 字段区分），agent-core 侧 runCode 工具按字段归一化。
 *
 * @param stdout    标准输出，截断至 8KB（截断时尾部注明）
 * @param stderr    标准错误，截断至 8KB（截断时尾部注明）
 * @param exitCode  容器内进程退出码（被强杀时为 137 等信号语义）
 * @param timedOut  墙钟或 CPU 超时强杀为 true
 * @param wallMs    墙钟耗时毫秒
 * @param cpuMs     CPU 时间毫秒（容器 stats 口径——与墙钟各防一种绕过）
 * @param errorCode 沙箱侧错误码，可空：sandbox.resource_exceeded / sandbox.launch_failed / sandbox.internal
 */
public record ExecuteResponse(
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut,
        long wallMs,
        long cpuMs,
        String errorCode
) {

    /**
     * 截断标注（契约：截断时尾部注明）
     */
    public static final String TRUNCATED_SUFFIX = "\n[已截断]";

    public static final String ERROR_RESOURCE_EXCEEDED = "sandbox.resource_exceeded";
    public static final String ERROR_LAUNCH_FAILED = "sandbox.launch_failed";
    public static final String ERROR_INTERNAL = "sandbox.internal";
}
