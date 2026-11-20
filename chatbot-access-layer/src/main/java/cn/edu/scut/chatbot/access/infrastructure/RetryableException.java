package cn.edu.scut.chatbot.access.infrastructure;

/**
 * 瞬时故障：网络抖动、超时、中间件暂不可达——调用方可按退避策略重试
 * （设计方案 3.4.8；接入层典型场景：Redis 写入失败、协议端连接失败）。
 */
public class RetryableException extends ChatBotException {

    public RetryableException(String errorCode, String message) {
        super(errorCode, message);
    }

    public RetryableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
