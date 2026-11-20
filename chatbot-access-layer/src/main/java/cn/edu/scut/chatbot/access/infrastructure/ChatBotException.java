package cn.edu.scut.chatbot.access.infrastructure;

/**
 * 接入层技术异常基类：携带点分短码 errorCode，进 ERROR 日志（设计方案 3.4.8 统一异常层次
 * 在接入层进程内的同构实现——该组件不进 contract，两端各自实现是既定纪律）。
 *
 * <p>使用纪律：errorCode 只描述「系统哪里坏了」，不承载业务分支；ERROR 级日志必须携带
 * errorCode 与 traceId，写日志时省掉错误码等于把这条日志踢出告警体系。
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
     * 点分短码错误码，如 adapter.stream.write_failed
     */
    public String getErrorCode() {
        return errorCode;
    }
}
