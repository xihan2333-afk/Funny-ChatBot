package cn.edu.scut.chatbot.access.infrastructure;

/**
 * 持久故障：配置错误、数据损坏、结构契约违反——重试不会成功，照实结算
 * （设计方案 3.4.8；接入层典型场景：信封违反非空清单、毒消息、雪花时钟回拨超限）。
 */
public class NonRetryableException extends ChatBotException {

    public NonRetryableException(String errorCode, String message) {
        super(errorCode, message);
    }

    public NonRetryableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
