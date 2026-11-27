package cn.edu.scut.chatbot.exception;

import java.util.Map;

/**
 * 携带结构化附加负载的业务异常（例如 {@code llm.embedding_dimension_mismatch} 需向管理端返回维度与受影响条数）。
 */
public class PayloadException extends ChatBotException {

    private final Map<String, Object> payload;

    public PayloadException(String errorCode, String message, Map<String, Object> payload) {
        super(errorCode, message);
        this.payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
