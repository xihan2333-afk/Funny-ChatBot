package cn.edu.scut.chatbot.exception;

/**
 * 主服务技术异常基类（设计方案 3.4.8 统一异常层次在 agent-core 进程内的落地）：
 * 携带点分短码 errorCode，进 ERROR 日志。与接入层的同名异常各自实现——两端不共享业务代码
 * 是接入隔离纪律，异常层次同构即可。
 *
 * <p>使用纪律（宪法「异常与错误码」节）：errorCode 只描述「系统哪里坏了」，不承载业务分支；
 * ERROR 级日志必须携带 errorCode 与 traceId——写日志时省掉错误码，等于把这条日志踢出告警体系。
 *
 * <p>可重试语义二分：子类 {@link RetryableException}（瞬时故障，调用方可退避重试）与
 * {@link NonRetryableException}（数据损坏或代码 bug，重试无意义、必须暴露给人）。
 */
public class ChatBotException extends RuntimeException {

    private final String errorCode;

    public ChatBotException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ChatBotException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 点分短码错误码，如 engine.turn.illegal_transition
     */
    public String getErrorCode() {
        return errorCode;
    }
}
