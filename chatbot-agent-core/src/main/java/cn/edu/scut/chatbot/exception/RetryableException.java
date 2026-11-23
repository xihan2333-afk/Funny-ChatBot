package cn.edu.scut.chatbot.exception;

/**
 * 可重试异常（设计方案 3.4.8）：瞬时故障（网络抖动、Redis 短暂不可达、DB 死锁）。
 * 调用方按退避策略重试；Effects 队列的可重试失败即本类。
 */
public class RetryableException extends ChatBotException {

    public RetryableException(String errorCode, String message) {
        super(errorCode, message);
    }

    public RetryableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
