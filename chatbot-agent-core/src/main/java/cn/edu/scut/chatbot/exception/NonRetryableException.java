package cn.edu.scut.chatbot.exception;

/**
 * 不可重试异常（设计方案 3.4.8）：数据损坏或代码 bug（状态机事实矛盾、时钟回拨超限、
 * 反序列化坏数据）。重试无意义，必须暴露给运维；Effects 队列遇本类直接置 failed 落告警。
 */
public class NonRetryableException extends ChatBotException {

    public NonRetryableException(String errorCode, String message) {
        super(errorCode, message);
    }

    public NonRetryableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
